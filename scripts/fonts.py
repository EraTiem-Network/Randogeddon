#!/usr/bin/env python3
"""Fetches the themes' fonts and makes the files the web app ships (webApp/src/wasmJsMain/resources/fonts/).

    docker run --rm -v "$PWD":/work -w /work python:3.12-slim sh -c "pip install -q fonttools && python scripts/fonts.py"

Every font is free to ship (SIL OFL 1.1, or Apache 2.0); its licence text goes beside the files (fonts/licences/).
One file per weight, named after the theme's code: <code>-regular.ttf and, if the font has one, <code>-bold.ttf.

Three ways, by what the licence allows:
  variable  a variable font without a reserved name: a regular (400) and a bold (700) instance of it, cut down to the
            Latin letters the app writes (a tenth of the size)
  static    separate regular and bold files without a reserved name: cut down the same way
  asis      a font with a Reserved Font Name (a changed copy may not keep the name): shipped as it is
A theme's font is named in `shared/…/shared/Fonts.kt`; a new one gets a line here and there.
"""
import io
import pathlib
import sys
import urllib.parse
import urllib.request
import zipfile

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

GOOGLE = "https://raw.githubusercontent.com/google/fonts/main/"
OUT = pathlib.Path("webApp/src/wasmJsMain/resources/fonts")
# Basic Latin, Latin-1, Latin Extended-A, and the punctuation, arrows and marks the app's texts use
UNICODES = "U+0020-007E,U+00A0-00FF,U+0100-017F,U+2010-2027,U+2030,U+2032-2033,U+2039-203A,U+20AC,U+2122,U+2190-2193,U+2212,U+2260,U+2264-2265,U+2713-2714,U+2717"

# code (the theme's): (family, way, licence path, files…)
FONTS = {
    "ocean": ("Mulish", "variable", "ofl/mulish/OFL.txt", "ofl/mulish/Mulish[wght].ttf"),
    "forest": ("Roboto Slab", "variable", "apache/robotoslab/LICENSE.txt", "apache/robotoslab/RobotoSlab[wght].ttf"),
    "ember": ("Alegreya Sans", "static", "ofl/alegreyasans/OFL.txt", "ofl/alegreyasans/AlegreyaSans-Regular.ttf", "ofl/alegreyasans/AlegreyaSans-Bold.ttf"),
    "contrast": ("Atkinson Hyperlegible", "static", "ofl/atkinsonhyperlegible/OFL.txt", "ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Regular.ttf", "ofl/atkinsonhyperlegible/AtkinsonHyperlegible-Bold.ttf"),
    "midnight": ("Space Grotesk", "variable", "ofl/spacegrotesk/OFL.txt", "ofl/spacegrotesk/SpaceGrotesk[wght].ttf"),
    "sakura": ("Varela Round", "asis", "ofl/varelaround/OFL.txt", "ofl/varelaround/VarelaRound-Regular.ttf"),
    "terminal": ("JetBrains Mono", "variable", "ofl/jetbrainsmono/OFL.txt", "ofl/jetbrainsmono/JetBrainsMono[wght].ttf"),
    "sepia": ("Literata", "variable", "ofl/literata/OFL.txt", "ofl/literata/Literata[opsz,wght].ttf"),
    "nes": ("Press Start 2P", "asis", "ofl/pressstart2p/OFL.txt", "ofl/pressstart2p/PressStart2P-Regular.ttf"),
    "snes": ("Pixelify Sans", "variable", "ofl/pixelifysans/OFL.txt", "ofl/pixelifysans/PixelifySans[wght].ttf"),
    "n64": ("Baloo 2", "variable", "ofl/baloo2/OFL.txt", "ofl/baloo2/Baloo2[wght].ttf"),
    "gamecube": ("Jost", "variable", "ofl/jost/OFL.txt", "ofl/jost/Jost[wght].ttf"),
    "wii": ("Nunito", "variable", "ofl/nunito/OFL.txt", "ofl/nunito/Nunito[wght].ttf"),
    "wiiu": ("Fredoka", "variable", "ofl/fredoka/OFL.txt", "ofl/fredoka/Fredoka[wdth,wght].ttf"),
    "switch": ("Rubik", "variable", "ofl/rubik/OFL.txt", "ofl/rubik/Rubik[wght].ttf"),
    "switch2": ("Figtree", "variable", "ofl/figtree/OFL.txt", "ofl/figtree/Figtree[wght].ttf"),
    "ps1": ("Chakra Petch", "static", "ofl/chakrapetch/OFL.txt", "ofl/chakrapetch/ChakraPetch-Regular.ttf", "ofl/chakrapetch/ChakraPetch-Bold.ttf"),
    "ps2": ("Orbitron", "asis", "ofl/orbitron/OFL.txt", "ofl/orbitron/Orbitron[wght].ttf"),
    "ps3": ("Outfit", "variable", "ofl/outfit/OFL.txt", "ofl/outfit/Outfit[wght].ttf"),
    "ps4": ("Public Sans", "variable", "ofl/publicsans/OFL.txt", "ofl/publicsans/PublicSans[wght].ttf"),
    "ps5": ("Montserrat", "variable", "ofl/montserrat/OFL.txt", "ofl/montserrat/Montserrat[wght].ttf"),
    "xbox": ("Oxanium", "variable", "ofl/oxanium/OFL.txt", "ofl/oxanium/Oxanium[wght].ttf"),
    "xbox360": ("Exo 2", "variable", "ofl/exo2/OFL.txt", "ofl/exo2/Exo2[wght].ttf"),
    # Selawik comes as a zip from its own repository (regular and bold in it), with a reserved name
    "xbox-series": ("Selawik", "zip", "https://raw.githubusercontent.com/microsoft/Selawik/master/LICENSE.txt",
                    "https://github.com/microsoft/Selawik/releases/download/1.01/Selawik_Release.zip", "selawk.ttf", "selawkb.ttf"),
    "steamos": ("Inter", "variable", "ofl/inter/OFL.txt", "ofl/inter/Inter[opsz,wght].ttf"),
}


def fetch(path):
    url = path if path.startswith("https://") else GOOGLE + urllib.parse.quote(path)
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def cut(font):
    """[font] with only the app's letters (and the layout rules for them)."""
    options = subset.Options()
    options.layout_features = ["kern", "liga", "mark", "mkmk", "ccmp", "locl"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.glyph_names = False
    s = subset.Subsetter(options)
    s.populate(unicodes=subset.parse_unicodes(UNICODES))
    s.subset(font)
    return font


def save(font, code, weight):
    target = OUT / f"{code}-{weight}.ttf"
    font.save(target)
    return target


def instance(data, weight):
    font = TTFont(io.BytesIO(data))
    # Every other axis (width, optical size) at its default
    axes = {a.axisTag: None for a in font["fvar"].axes}
    axes["wght"] = weight
    return instancer.instantiateVariableFont(font, axes)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "licences").mkdir(exist_ok=True)
    only = set(sys.argv[1:])
    for code, (family, way, licence, *files) in FONTS.items():
        if only and code not in only:
            continue
        (OUT / "licences" / f"{family}.txt").write_bytes(fetch(licence))
        made = []
        if way == "variable":
            data = fetch(files[0])
            made = [save(cut(instance(data, 400)), code, "regular"), save(cut(instance(data, 700)), code, "bold")]
        elif way == "static":
            made = [save(cut(TTFont(io.BytesIO(fetch(f)))), code, w) for f, w in zip(files, ("regular", "bold"))]
        elif way == "asis":
            for f, w in zip(files, ("regular", "bold")):
                target = OUT / f"{code}-{w}.ttf"
                target.write_bytes(fetch(f))
                made.append(target)
        elif way == "zip":
            archive = zipfile.ZipFile(io.BytesIO(fetch(files[0])))
            names = {pathlib.PurePosixPath(n).name.lower(): n for n in archive.namelist()}
            for f, w in zip(files[1:], ("regular", "bold")):
                target = OUT / f"{code}-{w}.ttf"
                target.write_bytes(archive.read(names[f]))
                made.append(target)
        print(f"{code}: {family} ({way}) " + ", ".join(f"{t.name} {t.stat().st_size // 1024} KB" for t in made))


if __name__ == "__main__":
    main()
