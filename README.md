# JITModular

JITModular is a usage pattern of the [JITLib](http://doc.sccode.org/Overviews/JITLib.html) live-coding dialect of [SuperCollider](http://supercollider.github.io/) for monophonic, modular synthesis. It is a flexible way to play freely with synthesis designs, particularly complex designs with lots of controls.

JITLib represents signals by *NodeProxies*. In JITModular, each NodeProxy represents a synthesis module. Modules may be patched and repatched freely and modules' contents may be replaced at any time, using standard JITLib features. See the code example below.

### Extensions

Because JITModular distributes the processing for a single musical event among multiple modules, it is difficult to sequence "notes" using standard SuperCollider features. The extensions in this repository add two features to assist:

- A new event type, `\psSet`, that uses an Event's data to set all relevant control inputs of any NodeProxy within the given ProxySpace.

- A new "proxy role," also called `\psSet`, that produces `\psSet`-type events from a Pbind-style pattern and sequences them. The code example demonstrates the proxy role.

### Installation

1. Place the `JITModular/` folder into your user extension directory. If you don't know the user extension directory, run the instruction `Platform.userExtensionDir` within SC.

2. Add the following to your startup file. (In the SuperCollider IDE, use *File -> Open startup file*).

   ```
   (Platform.userExtensionDir +/+ "JITModular/psSet-event-type.scd").load;
   ```

3. Recompile the class library.

If you want to verify installation, the following instruction should return `PatternControl`. If it returns `nil`, then the file `psSet-event-type.scd` is not in the expected location.

```
AbstractPlayControl.proxyControlClasses[\psSet];
```

### Code example

```
s.boot;
p = ProxySpace.new.push;

// a stable output location,
// connected (by .play) to the hardware output
~out = { \in.ar(0!2) }; ~out.play;

// a sawtooth oscillator
~osc = { |freq = 60, amp = 0.1|
	Saw.ar(freq, amp).dup
};

// connect to output
~osc <>> ~out;

// detune it
~osc = { |freq = 60, amp = 0.1, detun = 1.006|
	Mix(Saw.ar(freq * [1, detun], amp)).dup
};

// a filter -- \in.ar(0!2) defines an audio input
~lpf = { |ffreq = 800, rq = 1|
	RLPF.ar(\in.ar(0!2), ffreq, rq)
};

// repatch
~osc <>> ~lpf <>> ~out;

// amp envelope
~eg = { |gt = 1|
	\in.ar(0!2) * EnvGen.kr(
		Env.adsr(0.01, 0.1, 0.6, 0.1),
		gt
	)
};
~lpf <>> ~eg <>> ~out;

// now too quiet
~osc.set(\amp, 0.2);

// run some notes
TempoClock.tempo = 124/60;

~player = \psSet -> Pbind(
	\skipArgs, [\amp],
	\midinote, Pseq([Pn(36, { rrand(3, 8) }), 39], inf),
	\dur, Pwrand([0.25, 0.5], [0.9, 0.1], inf)
);

// filter eg
~ffreq = 400;
~feg = { |t_trig = 1, freqMul = 25|
	var eg = EnvGen.kr(
		Env.perc(0.01, 0.1, level: freqMul),
		t_trig, levelBias: 1
	);
	(~ffreq.kr(1) * eg).clip(20, 20000)
};
	
	// patch the filter envelope to the filter's frequency input
~lpf.set(\ffreq, ~feg);

~feg.set(\freqMul, 40);
~lpf.set(\rq, 0.1);

// change the pattern to accent only some notes
~player = \psSet -> Pbind(
	\skipArgs, [\amp],
	\midinote, Pseq([Pn(36, { rrand(3, 8) }), 39], inf),
	\dur, Pwrand([0.25, 0.5], [0.9, 0.1], inf),
	\t_trig, Pwrand([0, 1], [0.7, 0.3], inf)
);

// we're done, remove everything
p.clear;
p.pop;
```

### License

Please follow Creative Commons CC-NC-BY-SA licensing guidelines: You *may* make a derivative project based on this code, provided that you:

- Credit me (H. James Harkins);
- Share your project with a similar or more permissive license;
- Do not import the code directly into a commercial project, without explicit permission from me.
