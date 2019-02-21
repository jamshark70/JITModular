JITModPatch {
	var <>name;
	var <>proxyspace, <>buffers, <>midi;
	var <>doc;
	var <path;
	var <dirty = false;  // I'm not sure I can really support this?

	var controllers;  // track changes in proxyspace

	*new { |server, name|
		^super.new.init(server, name)
	}

	*newFrom { |archive|
		^super.new.initFromArchive(archive)
	}

	init { |server, argName, array|
		name = argName;
		if(server.isNil) { server = Server.default };
		proxyspace = StereoProxySpace(server, name);
		// buffers = JITModBufferSet.new;
		this.initDoc;
		this.initController;
		// JITModPatchGui(this);  // uses dependencies
	}

	initFromArchive { |archive|
		name = archive[\name];
		proxyspace = archive[\proxyspace];
		// buffers = archive[\buffers];
		midi = archive[\midi];
		this.initDoc(archive[\string]);
		this.initController;
		if(midi.notNil) { this.initMidiCtl };
		// JITModPatchGui(this);  // uses dependencies
	}

	initDoc { |string("")|
		doc = Document.new("JITModPatch: " ++ name, string, envir: proxyspace).front;
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
		});
		proxyspace.keysValuesDo { |key, proxy|
			makeCtl.(proxy);
		};
	}

	clear {
		// if(dirty) {};  // ???
		controllers.do { |ctl| ctl.remove };
		controllers.clear;
		proxyspace.clear;
		proxyspace.remove;  // take it out of the global collection, for 'load'
		// buffers.clear;
		midi.free;
		doc.close;
	}

	*load { |path|
		var new = this.new;
		if(path.notNil) {
			^new.load(path)
		};
		// else (btw, later implement default path)
		FileDialog({ |path| new.load(path) }, fileMode: 1, acceptMode: 0, stripResult: true);
		^new  // you can have it now but it will be ready later
	}

	load { |path|
		var file = File(path, "r"), code, archive;
		if(file.isOpen) {
			proxyspace.server.waitForBoot {
				protect {
					this.clear;
					code = file.readAllString;
					archive = code.interpret;
					this.initFromArchive(archive);
				} { |error|
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
			FileDialog({ |path| this.prSave(path) }, fileMode: 0, acceptMode: 1, stripResult: true);
		};
	}

	prSave { |path|
		var file = File(path, "w");
		if(file.isOpen) {
			protect {
				// file's end result should be the patch
				file << "var proxyspace = %.new(name: %), buffers, midi;\n\n"
				.format(proxyspace.class.name, name.asCompileString);
				file << "var doc = " <<< doc.string << ";\n";
				// buffers.save(path, file);
				// file << "buffers = JITModBufferSet.load(path);\n\n";
				if(midi.notNil) {
					file << "midi = ";
					midi.storeOn(file);
					file << "(proxyspace);\n";
				};
				file << "\nproxyspace.use {\n";
				proxyspace.use { proxyspace.storeOn2(file) };
				file << "\};\n";
				// result for loading, should embed real objects
				file << "(name: " <<< name << ", proxyspace: proxyspace, string: doc, midi: midi)\n";
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
}
