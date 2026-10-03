#!/bin/sh
# Нужны: JDK (лучше 8), Sun/Oracle WTK или любой midp/cldc api jar (midpapi20.jar, cldcapi11.jar),
# и preverify из WTK. Поправь пути ниже.
WTK=${WTK:-$HOME/WTK2.5.2}
API=$WTK/lib/midpapi20.jar:$WTK/lib/cldcapi11.jar
rm -rf build && mkdir -p build/classes build/pre
javac -source 1.3 -target 1.3 -bootclasspath $API -d build/classes src/*.java || exit 1
$WTK/bin/preverify -classpath $API -d build/pre build/classes || exit 1
cp res/data.bin build/pre/
jar cfm Celeste.jar MANIFEST.MF -C build/pre .
echo "SIZE: $(wc -c < Celeste.jar)"
cat > Celeste.jad <<JAD
MIDlet-1: Celeste, , CelesteMIDlet
MIDlet-Name: Celeste Classic
MIDlet-Vendor: Port
MIDlet-Version: 1.0.0
MIDlet-Jar-URL: Celeste.jar
MIDlet-Jar-Size: $(wc -c < Celeste.jar)
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
JAD
