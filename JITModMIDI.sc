JITModMIDI {
	var <proxyspace;
	var <channel;
	var uid, midiFuncs, notes;

	*new { |proxyspace, channel|
		if(MIDIClient.initialized.not) {
			MIDIIn.connectAll;
		};
		^super.new.init(proxyspace, channel)
	}

	*newByName { |proxyspace, device(""), name(""), channel|
		var dev;
		if(MIDIClient.initialized.not) {
			MIDIIn.connectAll;
		};
		dev = MIDIClient.sources.detect { |endpt|
			device.matchRegexp(endpt.device) and: { name.matchRegexp(endpt.name) }
		};
		^super.new.init(proxyspace, dev, channel)
	}

	init { |space, device, chan|
		if(device.notNil) { uid = device.uid };
		proxyspace = space;
		channel = chan;
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
			midiFuncs.put(name,
				(name: name, resp: MIDIFunc.perform(name, func, chan: chan, srcID: uid))
			);
		};
	}

	free {
		midiFuncs.do { |item| item[\resp].free };
	}

	channel_ { |chan|
		var new;
		if(chan.isNil or: { chan.inclusivelyBetween(0, 15) }) {
			channel = chan;
			midiFuncs.keysValuesDo { |key, item|
				var resp = item[\resp];
				new = MIDIFunc(resp.func, resp.msgNum, chan, resp.msgType, resp.srcID);
				resp.free;
				item[\resp] = new;
			};
		} {
			"MIDI channel should be 0-15, was %".format(chan).warn;
		}
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

	learnCtl { |name, spec|
		var learnFunc;
		learnFunc = MIDIFunc.cc({ |val, num|
			"Adding CC% for %\n".postf(num, name);
			this.addCtl(num, name, spec);
			learnFunc.free;
		}, srcID: uid);
	}

	addCtl { |num, name, spec|
		var key = (name ++ num).asSymbol,
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
		midiFuncs.put(key, (
			name: name, num: num, spec: spec,
			resp: MIDIFunc.cc({ |val, num|
				(type: \psSet, proxyspace: proxyspace, skipArgs: skip)
				.put(name, spec.map(val / 127.0))
				.play;
			}, num, chan: channel, srcID: uid)
		));
	}

	removeCtl { |num, name|
		var key = (name ++ num).asSymbol;
		midiFuncs[key][\resp].free;
		midiFuncs[key] = nil;
	}

	storeOn { |stream|
		stream << "{ |proxyspace|\n\tvar new = "
		<< this.class.name << "(proxyspace);\n";
		midiFuncs.keysValuesDo { |key, item|
			// noteOn/Off are created automatically, don't need to save
			if(#[noteOn, noteOff].includes(item[\name]).not) {
				stream << "\tnew.addCtl(" <<< item[\num] << ", "
				<<< item[\name] << ", "
				<<< item[\spec] << ");\n";
			};
		};
		stream << "}.value";
	}
}
