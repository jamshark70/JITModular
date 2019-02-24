JITModPatch {
	var <>name;
	var <>proxyspace, <>buffers, <>midi;
	var <>doc;
	var <path;
	var <dirty = false;  // I'm not sure I can really support this?

	var controllers;  // track changes in proxyspace
	var bufPairs;

	*new { |server, name|
		^super.new.init(server, name)
	}

	*newFrom { |archive|
		^super.new.initFromArchive(archive)
	}

	init { |server, argName, array|
		name = argName;
		if(server.isNil) { server = Server.default };
		// .load boots the server automatically; .new doesn't
		if(server.serverRunning.not) { server.boot };
		proxyspace = StereoProxySpace(server, name);
		buffers = JITModBufferSet(this);
		this.initDoc;
		this.initController;
		proxyspace.use { proxyspace.gui };
		// JITModPatchGui(this);  // uses dependencies
		this.dirty = false;
	}

	initFromArchive { |archive|
		name = archive[\name];
		proxyspace = archive[\proxyspace];
		buffers = archive[\buffers] ?? { JITModBufferSet(this) };
		bufPairs = buffers.asKeyValuePairs;
		midi = archive[\midi];
		this.initDoc(archive[\string]);
		this.initController;
		if(midi.notNil) { this.initMidiCtl };
		proxyspace.use { proxyspace.gui };
		// JITModPatchGui(this);  // uses dependencies
		this.dirty = false;
	}

	initDoc { |string("")|
		doc = Document.new("JITModPatch: " ++ name, string, envir: proxyspace);
	}

	initController {
		var makeCtl = { |proxy|
			// we might not be in the environment at this point
			var key = proxyspace.use { proxy.key };
			controllers[key] = SimpleController(proxy)
			.put(\source, { this.dirty = true })
			// .put(\clear, { ... remove ctl? ... })
		};
		if(controllers.isNil) { controllers = IdentityDictionary.new };
		controllers[\proxyspace] = SimpleController(proxyspace)
		.put(\newProxy, { |obj, what, proxy|
			// add buffers into nodemap
			// this will add weight to the saved file but, no choice
			makeCtl.(proxy);
			if(bufPairs.notNil) { proxy.set(*bufPairs) };
			this.dirty = true;
		});
		proxyspace.keysValuesDo { |key, proxy|
			makeCtl.(proxy);
		};
		controllers[\buffers] = SimpleController(buffers)
		.put(\addBuffer, {
			bufPairs = buffers.asKeyValuePairs;
			proxyspace.do { |proxy| proxy.set(*bufPairs) };
		})
		.put(\removeBuffer, {
			bufPairs = buffers.asKeyValuePairs;
			// I don't know how to remove something from a nodeMap
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
		doc.close;
		this.changed(\didFree);
	}

	*load { |path|
		var new = this.new;
		if(path.notNil) {
			^new.load(path)
		};
		// else (btw, later implement default path)
		FileDialog({ |path| new.load(path) }, fileMode: 1, acceptMode: 0, stripResult: true,
			path: Archive.at(\JITModPatch, \lastPath).tryPerform(\dirname));
		^new  // you can have it now but it will be ready later
	}

	load { |p|
		var file = File(p, "r"), code, archive, saveExecutingPath;
		if(file.isOpen) {
			this.path = p;
			proxyspace.server.waitForBoot {
				protect {
					this.clear;
					code = file.readAllString;
					saveExecutingPath = thisProcess.nowExecutingPath;
					thisProcess.nowExecutingPath = p;
					archive = code.interpret;
					this.initFromArchive(archive);
				} { |error|
					thisProcess.nowExecutingPath = saveExecutingPath;
					file.close;
					defer {  // defer to allow error to clear before handling
						if(error.notNil) {
							this.changed(\load, \error, error);
						} {
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
				file << "\nproxyspace.use {\n";
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
						this.changed(\save, \success);
					}
				};
			};
		} {
			"JITModPatch:% could not open '%' for saving".format(name, path).warn;
			this.changed(\save, \openFailed);
		}
	}

	path_ { |p|
		path = p;
		Archive.put(\JITModPatch, \lastPath, path);
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
			midi = JITModMIDI.newByName(proxyspace, device, name, channel);
		} {
			midi = JITModMIDI(proxyspace, channel);
		};
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
		var buf = Buffer.read(proxyspace.server, path, startFrame, numFrames, action);
		buffers.put(name, buf);
		^buf
	}

	readBufChannel { |name, path, startFrame = 0, numFrames = -1, channels, action|
		var buf;
		if(channels.isNil) {
			Error("JITModPatch:readBufChannel: Please supply a 'channels' array").throw;
		};
		buf = Buffer.readChannel(proxyspace.server, path, startFrame, numFrames, channels, action);
		buffers.put(name, buf);
		^buf
	}

	addBuf { |name, buffer, replace = false|
		buffers.put(name, buffer, replace);
	}

	freeBuf { |name|
		buffers.removeAt(name);
	}
}

JITModPatchGui {
	var <model,
	<view,
	saveButton, saveAsButton, loadButton,
	controllers;

	*new { |model, parent, bounds|
		var view;
		if(parent.isNil) {
			parent = Window("JITModPatch: %".format(model.name), Rect(800, 200, 500, 400)).front;
			if(bounds.isNil) { bounds = parent.view.bounds.insetBy(5, 5) };
		};
		view = View(parent, bounds);
		^super.newCopyArgs(model).init(view)
	}

	*newForLayout { |model|
		var view = View();
		^super.newCopyArgs(model).init(view)
	}

	init { |argView|
		view = argView;
		view.layout = VLayout(
			saveButton = Button(),
			saveAsButton = Button(),
			loadButton = Button()
		);
		saveButton.states_([["save"]])
		.action_({ model.save(model.path) });  // model.path may be nil; gives file dialog
		saveAsButton.states_([["save as"]])
		.action_({ model.save(nil) });
		loadButton.states_([["load"]])
		.action_({
			FileDialog({ |path| model.load(path) }, fileMode: 1, acceptMode: 0, stripResult: true,
				path: Archive.at(\JITModPatch, \lastPath).tryPerform(\dirname));
		});
		controllers = IdentityDictionary.new;
		controllers[\patch] = SimpleController(model)
		.put(\dirty, { |obj, what, bool|
			defer { saveButton.enabled = bool };
		})
		.put(\didFree, {
			// this.close;
			controllers.do(_.remove);
		});
	}
}

JITModBufferSet {
	var <>model, <server, <buffers, <path;
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
			server = model.proxyspace.server;
		};
		buffers = IdentityDictionary.new;
	}

	clear {
		buffers.do(_.free);
		buffers.clear;
		this.changed(\didFree);
	}

	isEmpty { ^buffers.isEmpty }
	notEmpty { ^buffers.notEmpty }

	at { |name| ^buffers[name.asSymbol] }

	put { |name, buffer, replace(false)|
		var old;
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
		}
	}

	removeAt { |name|
		name = name.asSymbol;
		if(buffers[name].notNil) {
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
			buffer.write(dir +/+ name ++ ".wav", "wav", "float");
		};
		path = p;
	}

	load { |path|
		var dir = this.dir(path), name;
		if(File.exists(dir) and: { File.type(dir) == \directory }) {
			this.clear;
			(dir +/+ "*.wav").pathMatch.do { |path|
				name = path.basename.splitext[0].asSymbol;
				buffers[name] = Buffer.read(server, path);
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
			stream << "\nbuffers = JITModBufferSet(Server.default);\n";
			stream << "if(buffers.load(thisProcess.nowExecutingPath.dirname +/+ \"%\").not) { Error(\"Buffer loading failed\").throw };\n".format(path.basename);
			stream << "Server.default.sync;\n\n";
		} {
			Error("JITModBufferSet directory doesn't exist; storeOn can't proceed").throw;
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
		^JITModBufferRef(name, buffers[name])
	}
}

JITModBufferRef {
	var <name, <buffer;
	*new { |name, buffer|
		^super.newCopyArgs(name, buffer)
	}
	asControlInput { ^buffer.bufnum }
	storeOn { |stream|
		stream << "buffers.asRef(" <<< name << ")"
	}
	bufnum { ^buffer.bufnum }
	numFrames { ^buffer.numFrames }
	numChannels { ^buffer.numChannels }
	sampleRate { ^buffer.sampleRate }
	duration { ^buffer.duration }
}
