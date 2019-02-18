JITModMIDI {
	var <proxyspace;
	var uid, midiFuncs, notes;

	*new { |proxyspace|
		if(MIDIClient.initialized.not) {
			MIDIClient.init;
			MIDIIn.connectAll;
		};
		^super.new.init(proxyspace)
	}

	*newByName { |proxyspace, device(""), name("")|
		var dev;
		if(MIDIClient.initialized.not) {
			MIDIClient.init;
			MIDIIn.connectAll;
		};
		dev = MIDIClient.sources.detect { |endpt|
			device.matchRegexp(endpt.device) and: { name.matchRegexp(endpt.name) }
		};
		^super.new.init(proxyspace, dev)
	}

	init { |space, device|
		if(device.notNil) { uid = device.uid };
		proxyspace = space;
		notes = Array.new;
		midiFuncs = IdentityDictionary.new;
		[
			noteOn: { |vel, num|
				this.noteOn(num, vel)
			},
			noteOff: { |vel, num|
				this.noteOff(num, vel)
			}
		].pairsDo { |name, func|
			midiFuncs.put(name, MIDIFunc.perform(name, func, srcID: uid));
		};
	}

	free {
		midiFuncs.do { |resp| resp.free };
	}

	noteOn { |num, vel|
		notes.remove(num);  // just in case
		notes = notes.add(num);
		(type: \psSet, proxyspace: proxyspace, midinote: num, amp: vel / 127.0,
			skipArgs: #[pan], gt: 1, t_trig: 1, sustain: inf).play;
	}

	noteOff { |num, vel|
		notes.remove(num);
		if(notes.size > 0) {
			(type: \psSet, proxyspace: proxyspace, midinote: notes.last,
				skipArgs: #[amp, pan], gt: 1, t_trig: 0, sustain: inf).play;
		} {
			(type: \psSet, proxyspace: proxyspace,
				skipArgs: #[freq, amp, pan], gt: 0, t_trig: 0, sustain: inf).play;
		}
	}

	addCtl { |num, name, spec|
		var key = ("name" ++ num).asSymbol,
		skip = [\freq, \amp, \pan, \gt, \t_trig],
		ctl;
		skip.remove(name);
		if(spec.isNil) {
			block { |break|
				proxyspace.keysValuesDo { |key, obj|
					case
					{ key == name and: { obj.source.isNumber } } {
						spec = obj.getSpec('#');
					}
					{ obj.controlNames.detect { |cn| cn.name == name }.notNil } {
						spec = obj.getSpec(name);
					};
					if(spec.notNil) { break.() };
				};
			};
		};
		spec = spec.asSpec;
		midiFuncs.put(key, MIDIFunc.cc({ |val, num|
			(type: \psSet, proxyspace: proxyspace, skipArgs: skip)
			.put(name, spec.map(val / 127.0))
			.play;
		}, num, srcID: uid));
	}

	removeCtl { |num, name|
		var key = ("name" ++ num).asSymbol;
		midiFuncs[key].free;
		midiFuncs[key] = nil;
	}
}
