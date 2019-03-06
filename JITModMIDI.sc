JMMIDI {
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
		this.changed(\didFree);
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
			this.changed(\channel, chan);
		} {
			"MIDI channel should be 0-15, was %".format(chan).warn;
		}
	}

	noteOn { |num, vel|
		notes.remove(num);  // just in case
		notes = notes.add(num);
		(type: \psSet, proxyspace: proxyspace, midinote: num, amp: vel / 127.0,
			skipArgs: #[pan], gt: 1, t_trig: 1, sustain: inf).play;
		this.changed(\noteOn, num, vel);
	}

	noteOff { |num, vel|
		notes.remove(num);
		if(notes.size > 0) {
			(type: \psSet, proxyspace: proxyspace, midinote: notes.last,
				skipArgs: #[amp, pan], gt: 1, t_trig: 0, sustain: inf).play;
		} {
			(type: \psSet, proxyspace: proxyspace,
				skipArgs: #[freq, amp, pan], gt: 0, t_trig: 0, sustain: inf).play;
		};
		this.changed(\noteOff, num, notes.last);
	}

	cc { |key, val|
		var spec = midiFuncs[key];
		if(spec.notNil) {
			(type: \psSet, proxyspace: proxyspace, skipArgs: spec[\skipArgs])
			.put(spec[\name], spec[\spec].map(val / 127.0))
			.play;
			this.changed(\cc, spec[\num], val);
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
			name: name, num: num, spec: spec, skipArgs: skip,
			resp: MIDIFunc.cc({ |val, num|
				this.cc(key, val);
			}, num, chan: channel, srcID: uid)
		));
		this.changed(\addCtl, num, name, spec);
	}

	removeCtl { |num, name|
		var key = (name ++ num).asSymbol;
		midiFuncs[key][\resp].free;
		midiFuncs[key] = nil;
		this.changed(\removeCtl, num, name);
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

JMMidiView : SCViewHolder {
	var <model, <midi, layout,
	controllers,
	ccs;
	*new { |model|
		^super.new.init(model)
	}

	// http://sccode.org/1-50N: Stretchable ScrollView
	init { |argModel|
		model = argModel;
		ccs = MultiLevelIdentityDictionary.new;  // num --> name --> view
		this.view = ScrollView();  // assumes in a layout
		view.canvas = View();
		view.canvas.layout = layout = VLayout(
			StaticText().string_("MIDI Controllers").fixedHeight_(30),
			View()  // dummy spacer
		);
		controllers = IdentityDictionary.new;
		controllers[\model] = SimpleController(model)
		.put(\initedMidi, { |obj, what, midi|
			this.initMidi(midi);
		});
		if(model.midi.notNil) {
			this.initMidi(model.midi);
		};
	}

	initMidi { |argMidi|
		midi = argMidi;
		controllers[\midi] = SimpleController(midi)
		.put(\didFree, { this.remove; controllers.do(_.remove) })
		.put(\addCtl, { |obj, what, num, name, spec|
			this.addCtl(num, name, spec);
		})
		.put(\removeCtl, { |obj, what, num, name|
			this.removeCtl(num, name);
		})
		.put(\cc, { |obj, what, num, val|
			this.cc(num, val);
		});
	}

	addCtl { |num, name, spec|
		var new = JMMidiCtlView(this, num, name, spec);
		ccs.put(num, name, new);
		layout.insert(new.view, view.children.size - 1);
	}

	removeCtl { |num, name|
		ccs.at(num, name).remove;  // drop from GUI
		ccs.removeEmptyAt(num, name);  // drom from storage
	}

	cc { |num, val|
		ccs.at(num).do { |v| v.cc(val) }
	}
}

JMMidiCtlView : SCViewHolder {
	var <model, key, num, spec, nameView, numView, valView, slider;
	*new { |model, num, name, spec|
		^super.new.init(model, num, name, spec)
	}

	init { |argModel, argNum, name, argSpec|
		model = argModel;
		num = argNum;
		spec = argSpec;
		key = (name ++ num).asSymbol;
		view = View().fixedHeight_(30)
		.background_(Color.gray(0.6))  // debugging
		;
		view.layout = HLayout(
			nameView = StaticText().string_(name).fixedWidth_(120),
			numView = StaticText().string_("CC" ++ num).fixedWidth_(50),
			valView = NumberBox().enabled_(false).fixedWidth_(70),
			slider = Slider().orientation_(\horizontal)
		).margins_(2);
		slider.action = { |view|
			// model is the JMMidiView
			// model.midi is the JMMIDI object
			model.midi.cc(key, view.value * 127.0);
		};
	}

	cc { |val|
		var norm = val / 127.0,
		value = spec.map(norm);
		defer {
			valView.value = value;
			slider.value = norm;
		}
	}
}
