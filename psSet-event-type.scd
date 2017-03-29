Event.addEventType(\psSet, {
	var value,
	sustain = ~sustain.value,
	releaseHolder = ~releaseHolder,
	skipArgs = IdentitySet.with(\gate, \fadeTime, \out).addAll(~skipArgs);
	~freq = ~detunedFreq.value;
	~amp = ~amp.value;
	~proxyspace.keysValuesDo { |name, proxy|
		var ctl = proxy.objects[0];
		case  // most specific cases (ScalarSynthControl) should come first
		{ ctl.isKindOf(StreamControl) and: { ctl.source.isNumber } } {
			if(skipArgs.includes(name.asSymbol).not and: { (value = name.envirGet).notNil }) {
				proxy.source = value;
				if(name == \gt and: { sustain.notNil }) {
					if(releaseHolder.notNil) {
						releaseHolder[proxy] = thisThread.clock.beats + sustain;
					};
					thisThread.clock.sched(sustain, {
						if(releaseHolder.isNil or: { releaseHolder[proxy] ? 0 <= thisThread.clock.beats }) {
							proxy.source = 0;
						};
					});
				};
			};
		}
		{ ctl.isKindOf(SynthControl) } {
			proxy.controlNames.do { |cn|
				var cname = cn.name.asSymbol;
				if(skipArgs.includes(cname).not and: {  // if... don't skip me
					proxy.nodeMap[cname].isKindOf(BusPlug).not and: {  // and I'm not mapped to a proxy
						(value = cname.envirGet).notNil  // and I have a value
					}
				}) {
					proxy.set(cn.name, value);
					if(cn.name == \gt and: { sustain.notNil }) {
						if(releaseHolder.notNil) {
							releaseHolder[proxy] = thisThread.beats + sustain;
						};
						thisThread.clock.sched(sustain, {
							if(releaseHolder.isNil or: { releaseHolder[proxy] ? 0 <= thisThread.beats }) {
								proxy.set(cn.name, 0)
							};
						});
					};
				};
			};
		}
	};
});

AbstractPlayControl.proxyControlClasses[\psSet] = PatternControl;
AbstractPlayControl.buildMethods[\psSet] = { |pattern, proxy, channelOffset = 0, index|
	var watcher, rest = false;
	if(currentEnvironment.isKindOf(ProxySpace).not) {
		Error("\\psSet may not be used outside of a ProxySpace").throw;
	};
	// I don't have access to the stream player in here.
	// To silence the proxy upon 'stop', I have to set a flag
	// to turn events into rests.
	// If rest is true, all events will be rests.
	// Otherwise respect the existing isRest
	watcher = SimpleController(proxy)
	.put(\play, { rest = false })
	.put(\stop, { rest = true })
	.put(\clear, { rest = true; watcher.remove });
	// releaseHolder may well be a hack,
	// but it should actually be local to the pattern
	// must protect in a Ref
	// Pbindf will extract its value automatically
	// but not use composeEvents()
	Pbindf(pattern,
		\type, \psSet,
		\proxyspace, currentEnvironment,
		\releaseHolder, `(IdentityDictionary.new),
		#[isRest, gt, t_trig], Pfunc { |ev|
			[
				rest or: { ev[\isRest] ?? { false } },
				ev[\gt] ?? { 1 },
				ev[\t_trig] ?? { 1 }
			]
		}
	).buildForProxy(proxy, channelOffset, index)
};

StartUp.add {
	// condition should be true in 3.6, false in 3.7
	if(SynthDescLib.global['system_setbus_control_1'].isNil) {
		(1 .. SystemSynthDefs.numChannels).do { arg i;
			SynthDef("system_setbus_audio_" ++ i, { arg out = 0, fadeTime = 0, curve = 0, gate = 1;
				var values = NamedControl.ir(\values, 0 ! i);
				var env = Env([In.ar(out, i), values, values], [1, 0], curve, 1);
				var sig = EnvGen.ar(env, gate + Impulse.kr(0), timeScale: fadeTime, doneAction: 2);
				ReplaceOut.ar(out, sig);
			}, [\ir, \kr, \ir, \kr]).add;

			SynthDef("system_setbus_control_" ++ i, { arg out = 0, fadeTime = 0, curve = 0;
				var values = NamedControl.ir(\values, 0 ! i);
				var env = Env([In.kr(out, i), values], [1], curve);
				var sig = EnvGen.kr(env, timeScale: fadeTime, doneAction: 2);
				ReplaceOut.kr(out, sig);
			}, [\ir, \kr, \ir]).add;
		};
	};

	if(Event.default.eventTypes[\fadeBus].isNil) {
		Event.addEventType(\fadeBus, #{ |server|
			var bundle, instrument, rate, bus;
			var array = ~array.as(Array);
			var numChannels = min(~numChannels.value ? 1, array.size);
			if(numChannels > SystemSynthDefs.numChannels) {
				Error(
					"Can't set more than % channels with current setup in SystemSynthDefs."
					.format(SystemSynthDefs.numChannels)
				).throw;
			};
			if (~id.isNil) {
				~id = if(~rate == \audio) { server.nextNodeID } { -1 };
			};
			instrument = "system_setbus_%_%".format(~rate.value ? \control, numChannels);
			// addToTail, so that old bus value can be overridden:
			bundle = [9, instrument, ~id, 1, ~group.asControlInput,
				"values", array,
				"out", ~out.value,
				"fadeTime", ~fadeTime,
				"curve", ~curve
			].asOSCArgArray;
			~schedBundle.value(~lag, ~timingOffset, server, bundle);
			if(~rate == \audio) { // control rate synth frees by itself, because bus holds the value
				~stopServerNode = { server.sendBundle(server.latency, [\n_set, ~id, \gate, 0]) }
			};
		});
	};
};
