/***

Notice of GPL compliance

Much of the code in this file consists of modified versions of code from
the Just-In-Time Library, released under GPLv3 as part of the SuperCollider
package. I needed to override just a couple of critical places, but there
aren't many method hooks to do so.

Full credit to the authors of JITLib: Julian Rohrhuber, Alberto de Campo,
and many others.

I intend these methods as derivative works, released in source form back
to the community, under GPLv3. I am expressly not claiming authorship
for the portions of this code not written by myself.

-- H. James Harkins

***/

StereoNodeProxy : NodeProxy {
	defineBus { |rate = \audio, numChannels|
		// 2 unless otherwise specified, but only for audio proxies
		if(numChannels.isNil and: { rate == \audio }) {
			numChannels = 2;
		};
		^super.defineBus(rate, numChannels)
	}

	put { | index, obj, channelOffset = 0, extraArgs, now = true |
		var container, bundle, oldBus = bus;

		if(obj.isNil) { this.removeAt(index); ^this };
		if(index.isSequenceableCollection) {
			^this.putAll(obj.asArray, index, channelOffset)
		};

		bundle = MixedBundle.new;

		// this is the only thing I am changing
		// but 'put' is not well modularized so I have to copy the whole method
		container = obj.makeStereoProxyControl(channelOffset, this);
		container.build(this, index ? 0); // bus allocation happens here


		if(this.shouldAddObject(container, index)) {
			// server sync happens here if necessary
			if(server.serverRunning) { container.loadToBundle(bundle, server) } { loaded = false; };
			this.prepareOtherObjects(bundle, index, oldBus.notNil and: { oldBus !== bus });
		} {
			format("failed to add % to node proxy: %", obj, this).postln;
			^this
		};

		this.putNewObject(bundle, index, container, extraArgs, now);
		this.changed(\source, [obj, index, channelOffset, extraArgs, now]);

	}

}

StereoSynthDefControl : SynthDefControl {
	build { | proxy, orderIndex = 0 |
		var ok, rate, numChannels, outerDefControl, outerBuildProxy, controlNames;

		outerDefControl = NodeProxy.buildProxyControl;
		outerBuildProxy = NodeProxy.buildProxy;
		NodeProxy.buildProxyControl = this;
		NodeProxy.buildProxy = proxy;
		synthDef = source.buildForStereoProxy(proxy, channelOffset, orderIndex);
		NodeProxy.buildProxyControl = outerDefControl;
		outerBuildProxy = outerBuildProxy;

		rate = synthDef.rate;
		numChannels = synthDef.numChannels;
		ok = proxy.initBus(rate, numChannels);

		if(ok) {
			paused = proxy.paused;
			canReleaseSynth = synthDef.canReleaseSynth;
			canFreeSynth = synthDef.canFreeSynth;
			controlNames = synthDef.allControlNames;
			hasFadeTimeControl = controlNames.notNil and: {
				controlNames.any { |x| x.name === \fadeTime }
			};
		} {
			synthDef = nil;
			"synth def couldn't be built".warn;
		}
	}
}

StereoProxySynthDef : SynthDef {
	// pilfered from ProxySynthDef
	// I cannot inherit from ProxySynthDef for a whole ton of reasons
	var <>rate, <>numChannels;
	var <>canReleaseSynth, <>canFreeSynth;
	classvar <>sampleAccurate=false;

	*new { | name, func, rates, prependArgs, makeFadeEnv = true, channelOffset = 0,
		chanConstraint, rateConstraint |
		var def, rate, numChannels, output, isScalar, envgen, canFree, hasOwnGate;
		var hasGateArg=false, hasOutArg=false;
		var outerBuildSynthDef = UGen.buildSynthDef;
		def = super.new(name, {
			var  out, outCtl;

			// build the controls from args
			output = SynthDef.wrap(func, rates, prependArgs);
			output = output.asUGenInput;

			// protect from user error
			if(output.isKindOf(UGen) and: { output.synthDef != UGen.buildSynthDef }) {
				Error("Cannot share UGens between NodeProxies:" + output).throw
			};

			// protect from accidentally returning wrong array shapes
			if(output.containsSeqColl) {
				// try first unbubble singletons, these are ok
				output = output.collect { |each| each.unbubble };
				// otherwise flatten, but warn
				if(output.containsSeqColl) {
					"Synth output should be a flat array.\n%\nFlattened to: %\n"
					"See NodeProxy helpfile:routing\n\n".format(output, output.flat).warn;
					output = output.flat;
				};
			};

			output = output ? 0.0;

			// determine rate and numChannels of ugen func
			numChannels = output.numChannels;
			rate = output.rate;
			isScalar = rate === 'scalar';

			// check for out key. this is used by internal control.
			func.def.argNames.do { arg name;
				if(name === \out) { hasOutArg = true };
				if(name === \gate) { hasGateArg = true };
			};

			if(isScalar.not and: hasOutArg)
			{
				"out argument is provided internally!".error; // avoid overriding generated out
				^nil
			};

			// rate is only scalar if output was nil or if it was directly produced by an out ugen
			// this allows us to conveniently write constant numbers to a bus from the synth
			// if you want the synth to write nothing, return nil from the UGen function.

			if(isScalar and: { output.notNil } and: { UGen.buildSynthDef.children.last.isKindOf(AbstractOut).not }) {
				rate = 'control';
				isScalar = false;
			};

			//detect inner gates
			canFree = UGen.buildSynthDef.children.canFreeSynth;
			hasOwnGate = UGen.buildSynthDef.hasGateControl;
			makeFadeEnv = if(hasOwnGate and: { canFree.not }) {
				"The gate control should be able to free the synth!\n%".format(func).warn; false
			} {
				makeFadeEnv and: { (isScalar || canFree).not };
			};

			hasOwnGate = canFree && hasOwnGate; //only counts when it can actually free synth.
			if(hasOwnGate.not && hasGateArg) {
				"supplied gate overrides inner gate.".error;
				^nil
			};


			//"gate detection:".postln;
			//[\makeFadeEnv, makeFadeEnv, \canFree, canFree, \hasOwnGate, hasOwnGate].debug;

			// constrain the output to the right number of channels if supplied
			// if control rate, no channel wrapping is applied
			// and wrap it in a fade envelope
			envgen = if(makeFadeEnv) {
				EnvGate(i_level: 0, doneAction:2, curve: if(rate === 'audio') { 'sin' } { 'lin' })
			} { 1.0 };

			// again, I need to change only this bit
			// but this class has absolutely no modular construction at all
			// so I have no choice but to copy/paste the entire thing
			if(chanConstraint.isNil and: { rate == \audio }) {
				chanConstraint = 2;
			};
			if(chanConstraint.notNil and: { isScalar.not }) {
				case
				{ chanConstraint < numChannels } {
					if(rate === 'audio') {
						postf("%: wrapped channels from % to % channels\n", NodeProxy.buildProxy, numChannels, chanConstraint);
						output = NumChannels.ar(output, chanConstraint, true);
						numChannels = chanConstraint;
					} {
						postf("%: kept first % channels from % channel input\n", NodeProxy.buildProxy, chanConstraint, numChannels);
						output = output.keep(chanConstraint);
						numChannels = chanConstraint;
					}
				}
				{ chanConstraint > numChannels and: { rate == \audio } } {
					output = output.asArray.wrapExtend(chanConstraint);
					postf("%: expanded % channel% to %\n",
						NodeProxy.buildProxy,
						numChannels,
						if(numChannels == 1, "", "s"),
						chanConstraint
					);
					numChannels = chanConstraint;
				}
			};
			output = output * envgen;

			//"passed in rate: % output rate: %\n".postf(rateConstraint, rate);

			if(isScalar, {
				output
			}, {
				// rate adaption. \scalar proxy means neutral
				if(rateConstraint.notNil and: { rateConstraint != \scalar and: { rateConstraint !== rate }}) {
					if(rate === 'audio') {
						output = A2K.kr(output);
						rate = 'control';
						postf("%: adopted proxy input to control rate\n", NodeProxy.buildProxy);
					} {
						if(rateConstraint === 'audio') {
							output = K2A.ar(output);
							rate = 'audio';
							postf("%: adopted proxy input to audio rate\n", NodeProxy.buildProxy);
						}
					}
				};
				outCtl = Control.names(\out).ir(0) + channelOffset;
				(if(rate === \audio and: { sampleAccurate }) { OffsetOut } { Out }).multiNewList([rate, outCtl] ++ output)
			})
		});

		UGen.buildSynthDef = outerBuildSynthDef;

		// set the synthDefs instvars, so they can be used later

		def.rate = rate;
		def.numChannels = numChannels;
		def.canReleaseSynth = makeFadeEnv || hasOwnGate;
		def.canFreeSynth = def.canReleaseSynth || canFree;
		//[\defcanReleaseSynth, def.canReleaseSynth, \defcanFreeSynth, def.canFreeSynth].debug;
		^def
	}
}

StereoProxySpace : ProxySpace {
	makeProxy {
		// kinda ugly and will not work if I have to switch it to standard JITLib later
		// It's really not ideal to have a binding between JITLib and my patch GUI.
		// But 'defer' means that we have to save the loading state here and pass it back
		// using Library to avoid a classvar
		var loading = Library.at(\JITModPatch, \nowLoading);

		var proxy = StereoNodeProxy.new(server);
		this.initProxy(proxy);
		// callback; note, we haven't saved the proxy into the space yet
		// so we have to wait a tick
		// note also that, without the number, 'defer' doesn't defer :-\
		{ this.changed(\newProxy, proxy, loading) }.defer(0);
		^proxy
	}
}

// for disconnection
+ Nil {
	<>> { |target, adverb = \in|
		target.set(\in, 0);  // JITModPatch has logic to break the connection
	}
}
