JITModPatch {
	var <name;
	var <>proxyspace, <>server, <>buffers, <>midi;
	var <>doc, gui;
	var <path;
	var <dirty = false;  // I'm not sure I can really support this?

	var controllers;  // track changes in proxyspace

	*initClass {
		Class.initClassTree(Event);
		Class.initClassTree(AbstractPlayControl);
		(this.filenameSymbol.asString.dirname +/+ "psSet-event-type.scd").load;
	}

	*new { |server, name, loading = false|
		^super.new.init(server, name, loading: loading)
	}

	*newFrom { |archive|
		^super.new.initFromArchive(archive)
	}

	init { |argServer, argName, array, loading = false|
		name = argName;
		server = argServer;
		if(server.isNil) { server = Server.default };
		// .load boots the server automatically; .new doesn't
		NotificationCenter.registerOneShot(this, \ready, \init, {
			server.waitForBoot {  // just to be sure
				proxyspace = StereoProxySpace(server, name);
				buffers = JMBufferSet(this);
				this.initDoc;
				this.initController;
				JITModPatchGui(this);  // uses dependencies
				this.dirty = false;
			};
		});
		server.waitForBoot {
			if(loading.not) {
				NotificationCenter.notify(this, \ready);
			};
		};
	}

	initFromArchive { |archive|
		name = archive[\name];
		proxyspace = archive[\proxyspace];
		buffers = archive[\buffers] ?? { JMBufferSet(this) };
		midi = archive[\midi];
		this.initDoc(archive[\string]);
		this.initController;
		if(midi.notNil) { this.initMidiCtl };
		JITModPatchGui(this);  // uses dependencies
		// BUG: proxyspace hasn't finished initting
		// how to know when it's done? there is no clear pattern in timing
		this.dirty = false;
	}

	initDoc { |string("")|
		doc = Document.new(this.docTitle, string, envir: proxyspace);
		// seems we need a little time for string/envir to sync up
		AppClock.sched(0.5, { doc.front });
	}

	initController {
		var makeCtl = { |proxy|
			// we might not be in the environment at this point
			var key = proxyspace.use { proxy.key },
			setFunc = { |obj, what, args|
				var pairs = Array(args.size);
				args.pairsDo { |key, value|
					if(value.isKindOf(BusPlug)) {
						pairs.add(key).add(value);
					};
				};
				if(pairs.size > 0) {
					// send only mappings, not setting fixed values
					this.changed(\set, args)
				};
			};
			controllers[key] = SimpleController(proxy)
			.put(\source, { this.dirty = true })
			.put(\set, setFunc)
			.put(\map, setFunc);
			// .put(\clear, { ... remove ctl? ... })
			// per NodeProxy:xfadePerform, it appears that the \map or \set notification
			// may come from either the nodeproxy or the nodemap...???
			controllers[(key ++ "_nodeMap").asSymbol] = SimpleController(proxy.nodeMap)
			.put(\set, setFunc)
			.put(\map, setFunc);
		};
		if(controllers.isNil) {
			controllers = IdentityDictionary.new
		} {
			controllers.do(_.remove);
		};
		controllers[\proxyspace] = SimpleController(proxyspace)
		.put(\newProxy, { |obj, what, proxy, loading|
			makeCtl.(proxy);
			// if we are in the process of loading a patch, don't set 'dirty'
			if(loading.isNil) {
				this.dirty = true;
			};
		});
		proxyspace.keysValuesDo { |key, proxy|
			makeCtl.(proxy);
		};
		controllers[\buffers] = SimpleController(buffers)
		.put(\addBuffer, { |obj, what, name|
			// these should automatically 'dirty' the patch
			proxyspace.put(name, buffers.asRef(name));
		})
		.put(\removeBuffer, { |obj, what, name|
			proxyspace.at(name).clear;
		})
		// .put(\didFree, {})
		;
	}

	clear {
		// if(dirty) {};  // ???
		controllers.do { |ctl| ctl.remove };
		controllers.clear;
		proxyspace.clear;
		proxyspace.remove;  // take it out of the global collection, for 'load'
		buffers.clear;
		midi.free;
		doc.tryPerform(\close);  // may not have been initialized, if loading
		// gui.close; gui = nil;
		this.changed(\didFree);
	}

	*load { |path|
		var new = this.new(loading: true);
		if(path.notNil) {
			^new.load(path)
		};
		// else (btw, later implement default path)
		FileDialog(
			{ |path| new.load(path) },
			{ NotificationCenter.notify(new, \ready) },  // finish initing empty patch
			fileMode: 1, acceptMode: 0, stripResult: true,
			path: Archive.at(\JITModPatch, \lastPath).tryPerform(\dirname));
		^new  // you can have it now but it will be ready later
	}

	load { |p|
		var file = File(p, "r"), code, archive, saveExecutingPath;
		if(file.isOpen) {
			server.waitForBoot {
				Library.put(\JITModPatch, \nowLoading, this);
				protect {
					this.clear;
					code = file.readAllString;
					saveExecutingPath = thisProcess.nowExecutingPath;
					thisProcess.nowExecutingPath = p;
					archive = code.interpret;
					this.initFromArchive(archive);
				} { |error|
					Library.global.removeEmptyAt(\JITModPatch, \nowLoading);
					thisProcess.nowExecutingPath = saveExecutingPath;
					file.close;
					defer {  // defer to allow error to clear before handling
						if(error.notNil) {
							this.changed(\load, \error, error);
						} {
							this.path = p;
							this.changed(\load, \success);
						}
					};
				}
			};
		} {
			"JITModPatch:% could not open '%' for loading".format(name, path).warn;
			this.changed(\load, \openFailed);
		}
	}

	save { |path|
		if(path.notNil) {
			this.prSave(path)
		} {
			FileDialog({ |path| this.prSave(path) }, fileMode: 0, acceptMode: 1, stripResult: true,
				path: Archive.at(\JITModPatch, \lastPath).tryPerform(\dirname));
		};
	}

	prSave { |p|
		var file = File(p, "w");
		if(file.isOpen) {
			this.path = p;
			protect {
				// file's end result should be the patch
				file << "var proxyspace = %.new(name: %), buffers, midi;\n\n"
				.format(proxyspace.class.name, name.asCompileString);
				file << "var doc = " <<< doc.string << ";\n";
				if(buffers.notEmpty) {
					buffers.save(path);
					buffers.storeOn(file);
				};
				if(midi.notNil) {
					file << "midi = ";
					midi.storeOn(file);
					file << "(proxyspace);\n";
				};
				file << "\nproxyspace.use {\n\n";
				// guarantee that buffer proxies get populated first
				// otherwise patterns may look for ~xyz.source and find nothing
				buffers.buffers.keysValuesDo { |name, buf|
					file << "~" << name << " = buffers.asRef(" <<< name << ");\n";
				};
				file << "\n";
				proxyspace.use { proxyspace.storeOn2(file) };
				file << "\};\n";
				// result for loading, should embed real objects
				file << "(name: " <<< name << ", proxyspace: proxyspace, string: doc, midi: midi, buffers: buffers)\n";
			} { |error|
				file.close;
				defer {  // defer to allow error to clear before handling
					if(error.notNil) {
						this.changed(\save, \error, error);
					} {
						this.dirty = false;
						this.changed(\save, \success);
					}
				};
			};
		} {
			"JITModPatch:% could not open '%' for saving".format(name, path).warn;
			this.changed(\save, \openFailed);
		}
	}

	docTitle { ^"JITModPatch: " ++ (name ?? "Untitled") }

	name_ { |n|
		name = n;
		doc.title = this.docTitle;
		this.changed(\name, name);
	}

	path_ { |p|
		path = p;
		if(path.notNil) {
			Archive.put(\JITModPatch, \lastPath, path);
			if(name.isNil) { this.name = path.basename.splitext[0] };
		};
	}

	dirty_ { |bool|
		dirty = bool;
		this.changed(\dirty, bool);
	}

	// proxyspace access
	at { |key| ^proxyspace.at(key) }
	put { |key, obj| proxyspace.put(key, obj) }

	// midi
	initMidi { |device, name, channel|
		if(device.notNil) {
			midi = JMMIDI.newByName(proxyspace, device, name, channel);
		} {
			midi = JMMIDI(proxyspace, channel);
		};
		this.changed(\initedMidi, midi);
		this.dirty = true;
	}
	initMidiCtl {
		if(midi.isNil) { this.initMidi };
		controllers[\midi] = SimpleController(midi)
		.put(\didFree, { controllers[\midi].remove; midi = nil; });
		#[channel, addCtl, removeCtl].do { |key|
			controllers[\midi].put(key, { this.dirty = true });
		};
	}
	clearMidi {
		midi.free;
		midi = nil;
	}

	learnCtl { |name, spec|
		if(midi.isNil) { this.initMidi };
		midi.learnCtl(name, spec);
	}
	addCtl { |num, name, spec|
		if(midi.isNil) { this.initMidi };
		midi.addCtl(num, name, spec);
	}
	removeCtl { |num, name|
		if(midi.isNil) { this.initMidi };
		midi.removeCtl(num, name);
	}

	// buffers
	readBuf { |name, path, startFrame = 0, numFrames = -1, action|
		var buf = JMBuf(server),
		finish = this.prFinishBufAction(name, buf);
		buf.doOnInfo = {
			finish.value(true);
			try { action.value } { |err|
				err.reportError;
				"^^^ Error during readBuf action".warn;
			};
		};
		buf.allocRead(path, startFrame, numFrames, { |buf| ["/b_query", buf.bufnum] });
		^buf
	}

	readBufChannel { |name, path, startFrame = 0, numFrames = -1, channels, action|
		var buf, finish;
		if(channels.isNil) {
			Error("JITModPatch:readBufChannel: Please supply a 'channels' array").throw;
		};
		buf = JMBuf(server);
		finish = this.prFinishBufAction(name, buf);
		buf.doOnInfo = {
			finish.value(true);
			try { action.value } { |err|
				err.reportError;
				"^^^ Error during readBuf action".warn;
			};
		};
		buf.allocReadChannel(path, startFrame, numFrames, channels, { |buf| ["/b_query", buf.bufnum] });
		^buf
	}

	prFinishBufAction { |name, buf|
		var status,
		failResp = OSCFunc({ |msg|
			if(msg[3] == buf.bufnum) {
				finish.(false);
			};
		}, '/fail', buf.server.addr),
		finish = { |success|
			failResp.free;
			if(success) {
				buffers.put(name, buf);  // 'buffers' sends \addBuffer
			} {
				buf.free;  // reuse bufnum
				buffers.changed(\bufReadFailed, name, buf);
			};
			status = success;
		};
		// schedule timeout
		AppClock.sched(3, {
			if(status.isNil) { finish.(false) };
			// if status is notNil, then we already did finish (true or false)
		});
		^finish
	}

	addBuf { |name, buffer, replace = true|
		buffers.put(name, buffer, replace);
	}

	freeBuf { |name|
		buffers.removeAt(name);
	}
}

JITModPatchGui {
	var <model,
	<view, window,
	psGuiView, psGui,
	saveButton, saveAsButton, loadButton, clearButton,
	midiButton, bufButton,
	stack,
	bufferView,  // not yet
	midiView,
	connView,
	controllers;

	*new { |model, parent, bounds|
		var view, iMadeWindow = false;
		if(parent.isNil) {
			// ProxyMixer may be up to 1086 wide
			parent = Window(model.docTitle, Rect(100, 200, 1120, 600))
			.userCanClose_(false)
			.front;
			iMadeWindow = true;
			if(bounds.isNil) { bounds = parent.view.bounds.insetBy(5, 5) };
		};
		view = View(parent, bounds);
		^super.newCopyArgs(model).init(view, if(iMadeWindow) { parent } { nil })
	}

	*newForLayout { |model|
		var view = View();
		^super.newCopyArgs(model).init(view)
	}

	init { |argView, argWindow|
		var saveWin;
		view = argView;
		window = argWindow;
		view.layout = VLayout(
			HLayout(
				[VLayout(
					HLayout(
						nil,
						bufButton = Button().fixedWidth_(120)
						.states_([["Buffers"]])
						.action_({ stack.index = 0 }),
						midiButton = Button().fixedWidth_(120)
						.states_([["MIDI controllers"]])
						.action_({ stack.index = 1 }),
						nil
					),
					stack = StackLayout(
						bufferView = JMBufferView(model),
						midiView = JMMidiView(model),
					).mode_(\stackOne),
				), stretch: 1],
				[VLayout(
					HLayout(
						nil,
						saveButton = Button().fixedWidth_(90),
						saveAsButton = Button().fixedWidth_(90),
						loadButton = Button().fixedWidth_(90),
						clearButton = Button().fixedWidth_(90),
						nil,
					),
					connView = TextView().editable_(false)
				), stretch: 1]
			),
			psGuiView = View().fixedHeight_(295)
		);
		model.proxyspace.use {
			// must be in the right environment
			psGui = ProxyMixer(model.proxyspace, 12, psGuiView, Rect(0, 0, 1090, 275));
		};
		saveButton.states_([["save"]])
		.action_({ model.save(model.path) });  // model.path may be nil; gives file dialog
		saveAsButton.states_([["save as"]])
		.action_({ model.save(nil) });
		loadButton.states_([["load"]])
		.action_({
			FileDialog({ |path| model.load(path) }, fileMode: 1, acceptMode: 0, stripResult: true,
				path: Archive.at(\JITModPatch, \lastPath).tryPerform(\dirname));
		});
		clearButton.states_([["quit"]])
		.action_({
			if(model.dirty) {
				saveWin = Window("save?", Rect.aboutPoint(Window.screenBounds.center, 100, 60));
				saveWin.layout = VLayout(
					StaticText().align_(\center).string_("You have unsaved changes."),
					HLayout(
						nil,
						Button().states_([["save"]])
						.action_({
							saveWin.close;
							this.prSave(model.path, { model.clear });
						}),
						Button().states_([["save as"]])
						.action_({
							saveWin.close;
							this.prSave(model.path, { model.clear });
						}),
Button().states_([["discard"]])
						.action_({ saveWin.close; model.clear }),
						Button().states_([["cancel"]])
						.action_({ saveWin.close })
					)
				);
				saveWin.front;
			} {
				model.clear;
			}
		});
		controllers = IdentityDictionary.new;
		controllers[\patch] = SimpleController(model)
		.put(\dirty, { |obj, what, bool|
			defer { saveButton.enabled = bool };
		})
		.put(\set, { |args| this.updateConn(args) })
		.put(\name, { |obj, what, name|
			if(window.notNil) {
				defer {
					window.name = model.docTitle;
				};
			};
		})
		.put(\didFree, {
			this.close;
			controllers.do(_.remove);
		});
		this.updateConn;
	}

	// calls up to the model to save, and finishes with an action at the end
	prSave { |path, action|
		controllers[\patch].put(\save, { |obj, what, code, error|
			controllers[\patch].removeAt(\save);
			switch(code)
			{ \success } {
				action.value;
			}
			{ \openFailed } {
				this.errorWindow("File open failed", "Could not open the file");
			}
			{ \error } {
				this.errorWindow("Error during save", error.errorString)
			}
			{ this.errorWindow("Oops", "Unexpected save status") }
		});
		model.save(path);
	}

	errorWindow { |title, msg|
		var errWin;
		errWin = Window(title, Rect.aboutPoint(Window.screenBounds.center, 100, 60));
		errWin.layout = VLayout(
			StaticText().align_(\center).string_(msg),
			HLayout(
				nil,
				Button().states_([["OK"]]).action_({ errWin.close })
			)
		);
		errWin.front;
	}

	close {
		if(window.notNil) { window.close };
	}

	updateConn {
		// This is not modularized at all because I'm lazy
		var chains = Array.new, out = CollStream.new,
		makeConn = { |src, target, name|
			(src: src, target: target, name: name, srcRate: src.rate)
		},
		canConnect = { |a, b|
			a.srcRate == b.srcRate and: { a.target === b.src }
		},
		findChain = { |conn|
			chains.detect { |chain|
				canConnect.(conn, chain.first)
				or: {
					canConnect.(chain.last, conn)
				}
			};
		},
		// problem: cleaning 'c' array (we are iterating over it)
		scanLinks = {
			(1 .. chains.size - 1).do { |i|  // 1, 2, 3...
				if(chains[i].notNil) {
					block { |break|
						i.do { |j|  // 0, 1 .. i-1
							if(chains[j].notNil) {
								if(canConnect.(chains[j].last, chains[i].first)) {
									// c[i] goes to end of c[j]
									chains[i].do { |conn| chains[j].add(conn) };
									chains[i] = nil;
									break.();
								} {
									if(canConnect.(chains[i].last, chains[j].first)) {
										// c[i] goes to head of c[j]
										chains[i].reverseDo { |conn| chains[j].addFirst(conn) };
										chains[i] = nil;
										break.();
									}
								};
							};
						};
					};
				};
			};
			chains = chains.reject(_.isNil);
		};
		model.proxyspace.keysValuesDo { |key, proxy|
			var conn, chain;
			proxy.nodeMap.keysValuesDo { |key, src|
				if(src.isKindOf(BusPlug)) {
					conn = makeConn.(src, proxy, key);
					chain = findChain.(conn);
					if(chain.notNil) {
						if(canConnect.(chain.last, conn)) {
							chain.add(conn);
						} {
							chain.addFirst(conn);
						};
						scanLinks.();
					} {
						chains = chains.add(LinkedList.new.addFirst(conn));
					};
				};
			};
		};
		model.proxyspace.use {
			chains.do { |chain|
				out << chain.first.src.asCompileString;
				chain.do { |conn, i|
					out << " <>>";
					if(conn.name != \in) {
						out << "." << conn.name;
					};
					out << " " << conn.target.asCompileString;
				};
				out << "\n";
			};
		};
		defer { connView.string = out.collection };
	}
}

JMBufferSet {
	var <>model, <server, <buffers, <path, controllers;
	// path should be the full path of the .jitmod file -- set in 'save'

	// for loading, we won't have the JITModPatch right now
	// so, hack: 'model' may be a server. You fill in the 'model' later
	*new { |model|
		^super.newCopyArgs(model).init;
	}

	init {
		if(model.isKindOf(Server)) {
			server = model;
			model = nil;
		} {
			server = model.server;
		};
		buffers = IdentityDictionary.new;
		controllers = IdentityDictionary.new;
	}

	clear {
		buffers.do(_.free);
		buffers.clear;
		this.changed(\didFree);
	}

	isEmpty { ^buffers.isEmpty }
	notEmpty { ^buffers.notEmpty }

	at { |name| ^buffers[name.asSymbol] }

	put { |name, buffer, replace(true)|
		var old;
		if(buffer.isMemberOf(Buffer)) {
			"Buffers may display incorrectly; use JMBuf instead (name = %)"
			.format(name.asCompileString).warn;
		};
		name = name.asSymbol;
		if(buffer.isNil) { ^this.removeAt(name) };
		old = buffers[name];
		if(replace.not and: { old.notNil }) {
			"Buffer '%' already exists; use a different name".format(name).warn;
		} {
			// wait a bit, to allow new bufnum to propagate out to synths
			{ old.free }.defer(1);
			buffers[name] = buffer;
			this.changed(\addBuffer, name, buffer);
		};
		controllers[buffer.bufnum] = SimpleController(buffer)
		.put(\done, { |obj, what, cmd, reallyDone|
			if(reallyDone == true) {
				this.changed(\bufferContentsChanged, obj, cmd, reallyDone);
			};
		});
	}

	removeAt { |name|
		name = name.asSymbol;
		if(buffers[name].notNil) {
			controllers[buffers[name].bufnum].remove;
			controllers[buffers[name].bufnum] = nil;
			buffers[name].free;
			buffers[name] = nil;
			this.changed(\removeBuffer, name);
		}
	}

	dir { |p|
		^(p.dirname +/+ p.basename.splitext[0] ++ "_buffers")
	}
	save { |p|  // p = path to .jitmod file, not directory!
		var dir = this.dir(p);
		if(File.exists(dir) and: { File.type(dir) != \directory }) {
			"% already exists and is not a directory; can't save here".format(dir).warn;
			^false
		};
		if(File.exists(dir)) {
			(dir +/+ "*").pathMatch.do { |path| File.delete(path) };
		} {
			File.mkdir(dir);
		};
		buffers.keysValuesDo { |name, buffer|
			var format;
			if(buffer.tryPerform(\isWavetable) == true) {
				name = name ++ "_wt";
				format = "float";
			} {
				format = "int16";
			};
			buffer.write(dir +/+ name ++ ".wav", "wav", format);
		};
		path = p;
	}

	load { |path|
		var dir = this.dir(path), name, key, wt;
		if(File.exists(dir) and: { File.type(dir) == \directory }) {
			this.clear;
			(dir +/+ "*.wav").pathMatch.do { |path|
				name = path.basename.splitext[0];
				wt = name.endsWith("_wt");
				if(wt) {
					key = name.drop(-3).asSymbol;
				} {
					key = name.asSymbol;
				};
				buffers[key] = JMBuf.read(server, path);
				if(wt) {
					buffers[key].isWavetable = true;
				};
			};
			^true
		} {
			"'%' directory doesn't exist; can't load buffers".format(dir).warn;
			^false
		}
	}

	storeOn { |stream|
		// user should have called 'save' already
		// we'll check
		var dir;
		if(path.notNil) { dir = this.dir(path) };
		if(dir.notNil and: { File.exists(dir) and: { File.type(dir) == \directory } }) {
			// for loading, always relative path
			stream << "\nbuffers = JMBufferSet(Server.default);\n";
			stream << "if(buffers.load(thisProcess.nowExecutingPath).not) { Error(\"Buffer loading failed\").throw };\n";
			stream << "Server.default.sync;\n\n";
		} {
			Error("JMBufferSet directory doesn't exist; storeOn can't proceed").throw;
		};
	}

	asKeyValuePairs {
		var pairs = Array(buffers.size * 2);
		buffers.keysDo { |name|
			pairs.add(name).add(this.asRef(name));
		};
		^pairs
	}

	asRef { |name|
		^JMBufferRef(name, buffers[name])
	}
}

JMBufferRef {
	var <name, <buffer;
	*new { |name, buffer|
		^super.newCopyArgs(name, buffer)
	}
	asControlInput { ^buffer.bufnum }
	storeOn { |stream|
		stream << "buffers.asRef(" <<< name << ")"
	}
	printOn { |stream|
		stream << "JMBufferRef(" <<< name << ", " << buffer << ")"
	}
	bufnum { ^buffer.bufnum }
	numFrames { ^buffer.numFrames }
	numChannels { ^buffer.numChannels }
	sampleRate { ^buffer.sampleRate }
	duration { ^buffer.duration }

	// save buffer refs in proxies; set bus to bufnum
	// see Array:buildForProxy - returns bus-setting Event
	// for bufnums we do not want to xfade
	buildForProxy { |proxy, channelOffset = 0|
		proxy.initBus(\control, 1);
		^(type: \bus, array: [this.bufnum], out: proxy.bus.index)
	}

	proxyControlClass { ^StreamControl }
}

JMBuf : Buffer {
	var <>isWavetable = false, <pending, resp;

	// unfortunately there isn't much of a way to intervene in the *new process
	// given that all the creation methods call "super.newCopyArgs" instead of "this"
	// hack-a-rama
	cache {
		super.cache;
		if(resp.isNil) {
			resp = OSCFunc({ |msg|
				var reallyDone = (msg[1] == pending);
				if(reallyDone) { pending = nil };
				this.changed(\done, msg[1], reallyDone);
			}, '/done', server.addr, argTemplate: [nil, bufnum]);
		};
	}

	uncache {
		resp.free;
		resp = nil;
		super.uncache;
	}

	allocMsg { arg completionMessage;
		pending = \alloc;
		^super.allocMsg(completionMessage)
	}

	// unfortunately the wavetable-generated methods are not modularized at all
	normalize { arg newmax = 1, asWavetable = false;
		super.normalize(newmax, asWavetable);
		isWavetable = asWavetable;
		pending = '/normalize';
		// this.changed(\contents);
	}

	normalizeMsg { arg newmax = 1, asWavetable = false;
		var result = super.normalize(newmax, asWavetable);
		isWavetable = asWavetable;
		// this.changed(\contents);  // assuming it will be sent, caller waits for /done
		pending = '/normalize';
		^result
	}

	gen { arg genCommand, genArgs, normalize = true, asWavetable = true, clearFirst = true;
		super.gen(genCommand, genArgs, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
	}

	genMsg { arg genCommand, genArgs, normalize = true, asWavetable = true, clearFirst = true;
		var result = super.gen(genCommand, genArgs, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
		^result
	}

	sine1 { arg amps, normalize = true, asWavetable = true, clearFirst = true;
		super.sine1(amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
	}

	sine2 { arg freqs, amps, normalize = true, asWavetable = true, clearFirst = true;
		super.sine2(freqs, amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
	}

	sine3 { arg freqs, amps, phases, normalize = true, asWavetable = true, clearFirst = true;
		super.sine3(freqs, amps, phases, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
	}

	cheby { arg amps, normalize = true, asWavetable = true, clearFirst = true;
		super.cheby(amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
	}

	sine1Msg { arg amps, normalize = true, asWavetable = true, clearFirst = true;
		var result = super.sine1Msg(amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
		^result
	}

	sine2Msg { arg freqs, amps, normalize = true, asWavetable = true, clearFirst = true;
		var result = super.sine2Msg(freqs, amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
		^result
	}

	sine3Msg { arg freqs, amps, phases, normalize = true, asWavetable = true, clearFirst = true;
		var result = super.sine3Msg(freqs, amps, phases, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
		^result
	}

	chebyMsg { arg amps, normalize = true, asWavetable = true, clearFirst = true;
		var result = super.chebyMsg(amps, normalize, asWavetable, clearFirst);
		isWavetable = asWavetable;
		pending = '/b_gen';
		// this.changed(\contents);
		^result
	}
}

JMBufferView : SCViewHolder {
	var model, buffers;
	var listView, items, sfView, selectionView, controllers;
	var readButton, replaceButton, deleteButton;
	var current;

	*new { |model| ^super.new.init(model) }

	init { |argModel|
		model = argModel;
		buffers = model.buffers;
		view = View();
		view.layout = HLayout(
			VLayout(
				listView = ListView(),
				sfView = SoundFileView(),
				selectionView = TextField().fixedHeight_(20)
				.enabled_(false).align_(\center)
			),
			VLayout(
				nil,
				readButton = Button().fixedWidth_(80),
				replaceButton = Button().fixedWidth_(80),
				deleteButton = Button().fixedWidth_(80),
				nil,
			)
		);
		items = Array.new;
		this.updateItems.updateSfView;
		listView.action_({ |view|
			current = view.value;
			this.updateSfView(items[current][0]);
		});
		sfView.action_({ |view|
			var sel = view.selection(0);
			// selection update
			selectionView.string = "Start: %, length: %, end: %".format(
				sel[0], sel[1], sel.sum
			);
		});
		readButton.states_([["read"]]).action_({
			var item = this.currentItem, path, key;
			var win;
			win = Window("Buffer name", Rect.aboutPoint(Window.screenBounds.center, 120, 60)).front;
			win.layout = VLayout(
				StaticText().align_(\center).string_("Enter a buffer name"),
				TextField().align_(\center).action_({ |view|
					key = view.string;
					win.close;
					if(key.size > 0) {
						path = this.findPath;
						Dialog.openPanel({ |p|
							model.readBuf(key.asSymbol, p);
						}, path: path);
					}
				})
			);
		});
		replaceButton.states_([["replace"]]).action_({
			var item = this.currentItem,
			path = this.findPath;
			Dialog.openPanel({ |p|
				model.readBuf(item[0], p);  // same key
			}, path: path);
		});
		deleteButton.states_([["delete"]]).action_({
			var win, item = this.currentItem;
			if(item.notNil) {
				win = Window("Confirm", Rect.aboutPoint(Window.screenBounds.center, 120, 60)).front;
				win.layout = VLayout(
					StaticText().align_(\center).string_("Delete buffer %?".format(item[0])),
					HLayout(
						nil,
						Button().states_([["OK"]]).action_({
							win.close;
							buffers.removeAt(item[0]);
						}),
						Button().states_([["Cancel"]]).action_({ win.close }),
						nil
					)
				)
			};
		});
		controllers = IdentityDictionary.new;
		controllers[\buffers] = SimpleController(buffers)
		.put(\addBuffer, {
			defer { this.updateItems.updateSfView };  // might have replaced buffer contents
		})
		.put(\removeBuffer, {
			defer { this.updateItems.updateSfView };
		})
		.put(\bufferContentsChanged, { |obj, what, buf, cmd, reallyDone|
			var item;
			if(reallyDone == true) {
				item = this.currentItem;
				if(item.notNil and: { buffers[item[0]] === buf }) {
					defer { this.updateSfView };
				};
			};
		})
		.put(\didFree, {
			controllers.do(_.remove);
			this.remove;
		});
	}

	currentItem {
		^items[current ?? { -1 }] // items[listView.value ?? { -1 }]
	}

	findPath {
		var item = this.currentItem, buf, path;
		if(item.notNil) {
			buf = buffers[item[0]];
			if(buf.notNil) {
				path = buf.path;
			}
		};
		if(path.isNil) {
			if(model.path.notNil) {
				path = model.path;
			} {
				path = Archive.global.at(\JITModPatch, \lastPath);  // maybe nil
			};
		};
		^path.tryPerform(\dirname)
	}

	updateItems {
		var saveItem = this.currentItem, i;
		items = buffers.buffers.keys.as(Array).sort.collect { |key|
			var buf = buffers.at(key),
			base = if(buf.path.notNil) { buf.path.basename } { "n/a" };
			[key, "%: Buffer(%, %, %, %)".format(key,
				buf.numFrames, buf.numChannels, buf.sampleRate.asInteger, base
			)]
		};
		if(saveItem.notNil) {
			i = items.detectIndex { |item| item[0] == saveItem[0] };
		};
		listView.items = items.collect(_[1]);
		if(items.size > 0) {
			i = i ?? { 0 };
		};
		current = i;
		listView.value = i;
	}

	updateSfView {
		var item = this.currentItem, buf, file, temp;
		if(item.notNil) {
			buf = buffers.at(item[0]);
			if(buf.path.notNil and: { buf.tryPerform(\isWavetable) != true }) {
				// use readFileWithTask only if there's a file and it's not a wavetable
				file = SoundFile.openRead(buf.path);
				if(file.notNil) {
					protect {
						sfView.readFileWithTask(file, doneAction: { file.close });
					} { |error|
						if(error.notNil) { file.close };
					};
				}
			} {
				if(buf.tryPerform(\pending).notNil) { ^this };  // ignore in-process buffers
				buf.getToFloatArray(wait: -0.1, timeout: buf.numFrames * 0.0001, action: { |data|
					if(buf.tryPerform(\isWavetable) == true) {
						temp = FloatArray.new(data.size div: 2);
						data.pairsDo({ |a, b| temp.add(a+b) });
						data = temp;
					};
					defer {
						sfView.setData(data,
							channels: buf.numChannels,
							samplerate: buf.sampleRate.asInteger
						)
					}
				})
			};
		} {
			sfView.setData([0], channels: 1, samplerate: model.server.sampleRate.asInteger);
		};
	}
}

// intended as a more readable syntax for audio input sockets
// old: ~out = { \in.ar(0!2) };
// new: ~out = { JMInput.ar };
JMInput {
	*ar { |name = \in, numChannels = 2|
		^NamedControl.ar(name, Array.fill(numChannels, 0))
	}

	*kr { |name, numChannels = 1, default = 0|
		if(name.notNil) {
			default = default.asArray;
			if(default.size > numChannels) {
				"JMInput.kr: Default has too many channels, % channels discarded"
				.format(default.size - numChannels)
				.warn;
			};
			^NamedControl.kr(name, default.wrapExtend(numChannels))
		} {
			Error("JMInput.kr: Must specify a name").throw;
		}
	}
}
