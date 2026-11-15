#!/usr/bin/env python3
"""Generuje app/src/main/assets/pl_diacritics.txt (stripped<TAB>słowo z ogonkami).
Dane częstości: pakiet wordfreq (CC BY-SA 4.0). Użycie: pip install wordfreq && python3 tools/gen_dict.py"""
import re, os
from wordfreq import top_n_list, word_frequency

tr = str.maketrans('ąćęłńóśźż', 'acelnoszz')
words = [w for w in top_n_list('pl', 250000, wordlist='best') if re.fullmatch(r'[a-ząćęłńóśźż]{2,24}', w)]
groups = {}
for w in words:
    s = w.translate(tr)
    if s != w:
        groups.setdefault(s, []).append((word_frequency(w, 'pl'), w))
out = []
for s, vs in groups.items():
    vs.sort(reverse=True)
    f, w = vs[0]
    if len(vs) > 1 and vs[1][0] * 3 > f:   # dwuznaczne (np. zle -> złe/źle) pomijamy
        continue
    if f <= 8 * word_frequency(s, 'pl'):    # prawdziwe słowo bez ogonków (ze, mam, pisze) zostaje
        continue
    out.append((s, w))
out.sort()
path = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'pl_diacritics.txt')
with open(path, 'w', encoding='utf-8') as fh:
    for s, w in out:
        fh.write(f"{s}\t{w}\n")
print(len(out), 'wpisów')
