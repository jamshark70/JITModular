JMMIDI {
	var <proxyspace;
	var <channel;
	var uid, <midiFuncs, notes;

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
			(type: \psSet, proxyspace: proxyspace, midinote: num,
				skipArgs: #[freq, amp, pan], gt: 0, t_trig: 0, sustain: inf).play;
		};
		this.changed(\noteOff, num, notes.last);
	}

	cc { |key, val|
		var spec = midiFuncs[key];
		if(spec.notNil) {
			spec.put(\val, val);
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
		var key = this.key(num, name),
		skip = [\freq, \midinote, \amp, \pan, \gt, \t_trig],
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
		var key = this.key(num, name);
		midiFuncs[key][\resp].free;
		midiFuncs[key] = nil;
		this.changed(\removeCtl, num, name);
	}

	editCtl { |num, name, spec|
		var key = this.key(num, name),
		func = midiFuncs[key];
		if(func.notNil) {
			spec = spec.asSpec;
			func[\spec] = spec;
			this.changed(\editCtl, key, spec);
			// this.cc(key, func[\val]);  // no, this shouldn't change the real value
		};
	}

	key { |num, name| ^(name ++ num).asSymbol }

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
		// also need to wait a tick (this is AppClock, it's OK even if MIDI init takes awhile)
		// and return the JMMIDI object
		stream << "\t0.1.wait;\n\tnew;\n}.value";
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
	// a SCViewHolder may reasonably assume it's initting within a GUI thread
	init { |argModel|
		model = argModel;
		ccs = MultiLevelIdentityDictionary.new;  // num --> name --> view
		this.view = ScrollView();  // assumes in a layout
		view.canvas = View();
		view.canvas.layout = layout = VLayout(
			View()  // dummy spacer, should always be last
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
		.put(\editCtl, { |obj, what, key, spec|
			var cc = block { |break|
				ccs.leafDo { |path, ctlView|
					if(ctlView.key == key) { break.(ctlView) };
				};
				nil
			};
			if(cc.notNil) {
				cc.spec = spec;
			};
		})
		.put(\cc, { |obj, what, num, val|
			this.cc(num, val);
		});

		midi.midiFuncs.keysValuesDo { |key, func|
			if(#[noteOn, noteOff].includes(key).not) {
				this.addCtl(func[\num], func[\name], func[\spec]);
			};
		};
	}

	// but this may be called from anywhere, so it should defer
	// (known case: learnCtl --> incoming CC message --> dependency --> here)
	addCtl { |num, name, spec|
		var new;
		defer {
			new = JMMidiCtlView(this, num, name, spec);
			ccs.put(num, name, new);
			layout.insert(new.view, view.children.size - 1);
		};
	}

	removeCtl { |num, name|
		defer {
			ccs.at(num, name).remove;  // drop from GUI
			ccs.removeEmptyAt(num, name);  // drom from storage
		};
	}

	cc { |num, val|
		ccs.at(num).do { |v| v.cc(val) }
	}
}

JMMidiCtlView : SCViewHolder {
	var <model, <key, <num, <name, <spec,
	deleteButton, nameView, numView, valView, slider;

	*new { |model, num, name, spec|
		^super.new.init(model, num, name, spec)
	}

	init { |argModel, argNum, argName, argSpec|
		model = argModel;
		num = argNum;
		name = argName;
		spec = argSpec;
		key = model.midi.key(num, name);
		view = View().fixedHeight_(24)
		.background_(Color.gray(0.6))  // debugging
		;
		view.layout = HLayout(
			deleteButton = Button().states_([["X", Color.white, Color.red(0.4)]])
			.fixedSize_(Size(16, 16)),
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
		deleteButton.action = {
			model.midi.removeCtl(num, name);
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

	spec_ { |argSpec|
		var oldSpec = spec;
		spec = argSpec;
		// maybe can't access slider from this thread
		defer {
			slider.value = argSpec.unmap(oldSpec.map(slider.value));
		};
	}
}
