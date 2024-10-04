/***

Notice of GPL compliance

Much of the code in this file consists of modified versions of code from
the Just-In-Time Library, released under GPLv3 as part of the SuperCollider
package. I needed to override just a couple of critical places, but there
aren't many method hooks to do so.

Full credit to the authors of JITLib: Julian Rohrhuber, Alberto de Campo,
and many others.

I intend these methods as derivative works, released in source form back
to the community, under GPLv3. I am expressly not claiming authorship
for the portions of this code not written by myself.

-- H. James Harkins

***/

// work around a bug in ProxyMixer
// where edit buttons don't ensure the right environment

+ ProxyMixer {
	setEdButs { |isSmall = false|
		var saveEnvir;
		(arGuis ++ krGuis).do { |pxgui|
			pxgui.edBut.states_([
				["ed", skin.fontColor, skin.background],
				["ed", skin.fontColor, skin.onColor]])

			.action_({ arg btn, mod;
				saveEnvir = currentEnvironment;
				if([EnvironmentRedirect, Environment].any { |class| object.isKindOf(class) }) {
					currentEnvironment = object;
				};
				protect {
					if (mod.notNil and: { mod.isAlt }) {
						NdefGui(pxgui.object);
					} {
						this.switchSize(2, isSmall);
						editGui.object_(pxgui.object);
						arGuis.do { |gui| gui.edBut.value_(0) };
						krGuis.do { |gui| gui.edBut.value_(0) };
						btn.value_(1);
					};
				} {
					currentEnvironment = saveEnvir;
				};
			});
		};
	}
}
