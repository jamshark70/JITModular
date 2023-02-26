// this is turning into a bit of a god-object
// but I'm not going to refactor it today

JMMIDI_OSCBridge {
	classvar <profiles;

	var <patch, <func, <profile;
	var <midi, midictl, controls, learning;
	var aliveThread, addr;

	*initClass {
		profiles = IdentityDictionary[
			\openstage -> {
				var out = IdentityDictionary.new;
				var spec = [0, 1].asSpec;
				(1..24).do { |i|
					var digits = if(i >= 10) { 2 } { 1 };
					out.put(
						("/fader_" ++ String.fill(2 - digits, $0) ++ i).asSymbol,
						[i, spec]
					);  // oscpath --> [ccnum, inspec]
				};
				out
			}.value
		]
	}

	*new { |patch, profile(\openstage)|
		^super.new.init(patch, profile)
	}

	init { |argPatch, argProfile|
		patch = argPatch;
		profile = profiles[argProfile];
		if(profile.isNil) {
			Error("Nonexistent OSC profile").throw;
		};
		if(patch.midi.isNil) { patch.initMidi };
		midi = patch.midi;

		// midi.cc() depends on the 'key', which is name++num
		// this turns out to be inconvenient here
		// so I make a collection to map the OSC path to the key
		// (where the key becomes known at the time the parent adds a control)
		// assuming that 'num' matches the ccnum defined in the profile
		controls = IdentityDictionary.new;
		learning = IdentityDictionary.new;

		midictl = SimpleController(midi)
		.put(\didFree, { this.free })
		.put(\addCtl, { |obj, what, num, name, spec|
			this.addCtl(num, name, spec)
		})
		.put(\removeCtl, { |obj, what, num, name|
			this.removeCtl(num, name)
		})
		.put(\learning, { |obj, what, name, spec|
			learning.put(name, spec)
		})
		// .put(\learned, { |num, name|
		// 	learning.clear;
		// })
		;

		func = { |msg, time, replyAddr|
			var ctls = controls[msg[0]];
			var spec = profile[msg[0]];
			var value;
			// this condition eliminates irrelevant incoming messages
			// oscrecvfunc gets *everything*; ignore msgs we don't care about
			if(spec.notNil) {
				if(addr.isNil) { addr = replyAddr };
				if(ctls.notNil) {
					value = (spec[1].unmap(msg[1]) * 127.0).round.asInteger;
					ctls.do { |key|
						midi.cc(key, value)
					};
				};
				learning.keysValuesDo { |name, controlSpec|
					midi.addCtl(spec[0], name, controlSpec);
				};  // otherwise ignore unknown (bc this function gets *everything*)
				learning.clear;
			};
		};
		thisProcess.addOSCRecvFunc(func);
	}

	free {
		this.stopAliveThread;
		thisProcess.removeOSCRecvFunc(func);
		midictl.remove;
	}

	addCtl { |num, name, spec|  // don't really need spec
		var key = this.path(num, name);
		if(key.isNil) {
			"Unknown ccnum %".format(num).warn;
		} {
			if(controls[key].isNil) {
				// maybe multiple parms are on the same ccnum
				controls[key] = IdentitySet.new;
			};
			controls[key].add(midi.key(num, name))
		};
		this.startAliveThread;
	}

	removeCtl { |num, name|
		var key = this.path(num, name);
		if(key.isNil) {
			"Unknown ccnum %".format(num).warn;
		} {
			if(controls[key].notNil) {
				controls[key].remove(midi.key(num, name))
			};
		};
	}

	path { |num, name|
		^profile.keys.detect { |key|
			profile[key][0] == num
		}
	}

	// the problem is: if you have multiple nodeproxies
	// with the same arg name, the proxyspace doesn't sync
	// their values. It doesn't broadcast changes either.
	// So we have no guarantee of finding the one that changed latest,
	// hence no guarantee of sending the right value to OSC.
	// But a/ if there's only one proxy with that control, it's fine
	// and b/ if you're controlling it from MIDI/OSC, just stick with that.

	// JITLib synth args don't broadcast updates
	// polling is the only way
	startAliveThread {
		if(aliveThread.isNil) {
			aliveThread = SkipJack({
				controls.keysValuesDo { |path, keys|
					keys.do { |key|
						var mfunc = midi.midiFuncs[key];
						var name, value;
						if(mfunc.notNil) {
							name = mfunc[\name];
							value = block { |break|
								patch.proxyspace.keysValuesDo { |key, proxy|
									if(proxy.nodeMap[name].notNil) {
										break.(proxy.nodeMap[name]);
									};
								};
							};
							if(value.notNil) {
								// note: profile spec is for OSC-side range, not OK here
								value = mfunc[\spec].unmap(value);
								addr.sendMsg(path, value);
								midi.changed(\cc, mfunc[\num], (value * 127.0).round.asInteger);
							};
						};
					};
				};
			}, dt: 0.1, name: this.class.name.asString, clock: AppClock);
		};
	}

	stopAliveThread {
		aliveThread.stop;
		aliveThread = nil
	}
}
