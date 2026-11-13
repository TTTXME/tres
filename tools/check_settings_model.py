"""Run real, Android-independent settings logic using only a JDK; no SDK/device required."""
from pathlib import Path
import tempfile,subprocess,shutil
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as d:
    out=Path(d);stubs=out/'stubs';p=stubs/'com/scamera/app/app';p.mkdir(parents=True)
    (p/'ScameraApp.java').write_text('package com.scamera.app.app; public class ScameraApp { public static com.scamera.app.settings.SettingsManager getSettingsManagerStatic(){return null;} }')
    p=stubs/'com/scamera/app/settings';p.mkdir(parents=True)
    (p/'SettingsManager.java').write_text('package com.scamera.app.settings; public class SettingsManager { public static final String SCOPE_GLOBAL=""; public String getString(String a,String b,String c){return c;} }')
    p=stubs/'com/scamera/app/util';p.mkdir(parents=True)
    (p/'Lang.java').write_text('package com.scamera.app.util; public final class Lang {public static boolean ru(){return true;}public static String t(String ru,String en){return ru;}public static String t(Object context,String ru,String en){return ru;}}')
    production=root/'app/src/main/java/com/scamera/app/settings'
    files=[production/(s+'.java') for s in ('PreferenceNumber','SettingsNumericRules','SettingsAvailability')]
    javac=[shutil.which('javac')] if shutil.which('javac') else ['java','--module','jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run(javac+['-encoding','UTF-8','-d',str(out)]+[str(x) for x in files+list(stubs.rglob('*.java'))+[root/'tools/java/SettingsModelCheck.java']],check=True)
    subprocess.run(['java','-ea','-cp',str(out),'SettingsModelCheck'],check=True)
