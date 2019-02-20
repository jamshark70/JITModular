p = JITModPatch.load;

s.boot;
p = JITModPatch(s, \test);

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = { LFDNoise3.kr(14).exprange(55, 1200) };
~osc = { VarSaw.ar(~freq.kr, 0, Lag.kr(LFDNoise0.kr(8.7).range(0.01, 0.5), 0.05)) };
~osc <>> ~out;

~out = { \in.ar(0!2) }; ~out.play(vol: 0.2);
~freq = { LFDNoise3.kr(14).exprange(55, 1200) };
~osc = { |speed = 8.7|
	VarSaw.ar(~freq.kr, 0, Lag.kr(LFDNoise0.kr(speed).range(0.01, 0.5), 0.05))
};
~osc.addSpec(\speed, [1, 15, \exp]);
~osc <>> ~out;

p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");
p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch2.jitmod");
p.save(thisProcess.nowExecutingPath.dirname +/+ "testpatch3.jitmod");

p.clear;

p.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");

p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch.jitmod");
p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch2.jitmod");
p = JITModPatch.load(thisProcess.nowExecutingPath.dirname +/+ "testpatch3.jitmod");

p.proxyspace[\out].stop

p.proxyspace.use { p.proxyspace.storeOn2(Post) };
Halo.at(p.proxyspace[\osc])

m = JITModMIDI(p.proxyspace);
m.addCtl(1, \freq, \freq);
m.slotAt(\midiFuncs).at(\name1).chan

m.removeCtl(1, \freq);

m.addCtl(1, \speed, [1, 15, \exp]);

MIDIIn.doControlAction(MIDIClient.sources[0].uid, 0, 1, 100);

m.removeCtl(1, \speed);

MIDIIn.doControlAction(MIDIClient.sources[0].uid, 0, 1, 60);

m.channel = 0;

p.addCtl(1, \speed);

p.midi.postcs
p.midi.inspect

// halo checking
Halo.at(p.proxyspace[\osc])[\spec]

// huh. string problem?
d = Document.allDocuments.detect { |doc| doc.title.beginsWith("JITModPatch:") };
d.string
d.string(0, 1 << 24);
d.selectRange(0, 1 << 24);
d.string

p.doc.string