// Holy hell. SC-IDE core code depends on a method from wslib.
// So I have to re-provide it myself.

+ SequenceableCollection {
	asAscii {
		var out = String.new;
		this.do { |item|
			out = out ++ (item.tryPerform(\asAscii) ?? "");
		};
		^out
	}
}
