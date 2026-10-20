with open('app/src/main/assets/formas.txt', encoding='utf-8') as f:
    pass # we can check station distances

import math

METERS_PER_DEG_LAT = 111320.0
def dist(p1, p2):
    mlng = METERS_PER_DEG_LAT * math.cos(math.radians((p1[0] + p2[0]) * 0.5))
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

stops = {}
with open('app/src/main/assets/paradas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 5:
            stops[p[0].strip()] = (p[2].strip(), float(p[3]), float(p[4]))

horarios = {}
with open('app/src/main/assets/horarios.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 5: continue
        tid = p[0].strip()
        seq = int(p[1].strip())
        sid = p[4].strip()
        if tid not in horarios: horarios[tid] = []
        horarios[tid].append((seq, sid))

for tid in horarios: horarios[tid].sort(key=lambda x: x[0])

long_segments = set()
for tid, st in horarios.items():
    for i in range(len(st)-1):
        s1 = st[i][1]
        s2 = st[i+1][1]
        p1 = stops.get(s1)
        p2 = stops.get(s2)
        if p1 and p2:
            d = dist((p1[1], p1[2]), (p2[1], p2[2]))
            if d > 2000:
                long_segments.add((s1, p1[0], s2, p2[0], round(d)))

print('Station pairs with straight distance > 2000m:')
for s1, n1, s2, n2, d in sorted(long_segments, key=lambda x: -x[4]):
    print(f'{n1} ({s1}) -> {n2} ({s2}): {d}m')

