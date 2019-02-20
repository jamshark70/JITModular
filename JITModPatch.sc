JITModPatch {
	var <>name;
	var <>proxyspace, <>buffers, <>midi;
	var <>doc;
	var <path;
	var <dirty = false;  // I'm not sure I can really support this?

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
		// JITModPatchGui(this);  // uses dependencies
	}

	initFromArchive { |archive|
		name = archive[\name];
		proxyspace = archive[\proxyspace];
		// buffers = archive[\buffers];
		// midi = archive[\midi];
		this.initDoc(archive[\string]);
		// JITModPatchGui(this);  // uses dependencies
	}

	initDoc { |string("")|
		doc = Document.new("JITModPatch: " ++ name, string, envir: proxyspace).front;
	}

	clear {
		// if(dirty) {};  // ???
		proxyspace.clear;
		proxyspace.remove;  // take it out of the global collection, for 'load'
		// buffers.clear;
		// midi.clear;
		doc.close;
	}

	*load { |path|
		^this.new.load(path)
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
					file << ";\n";
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
}
