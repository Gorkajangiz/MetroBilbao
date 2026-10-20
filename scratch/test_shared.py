import math

METERS_PER_DEG_LAT = 111320.0
def dist(p1, p2):
    mlng = METERS_PER_DEG_LAT * math.cos(math.radians((p1[0] + p2[0]) * 0.5))
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

stops = []
with open('app/src/main/assets/paradas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 7 and p[6].strip() == '1':
            stops.append((p[0].strip(), p[2].strip(), float(p[3]), float(p[4])))

# Load ETX_PLE and BSR_KAB
shapes = {}
with open('app/src/main/assets/formas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 4: continue
        sid, lat, lon, seq = p[0].strip(), float(p[1]), float(p[2]), int(p[3])
        if sid not in shapes: shapes[sid] = []
        shapes[sid].append((seq, lat, lon))

l1 = [(lat, lon) for seq, lat, lon in sorted(shapes['ETX_PLE'])]
l2 = [(lat, lon) for seq, lat, lon in sorted(shapes['BSR_KAB'])]

def min_dist(p, poly):
    return min(dist(p, q) for q in poly)

shared_names = ["San Inazio", "Sarriko", "Deustu", "Santimami/San Mamés", "Indautxu", "Moyua", "Abando", "Zazpikaleak/Casco Viejo", "Santutxu", "Basarrate", "Bolueta", "Etxebarri"]

for sid, name, lat, lon in stops:
    for sname in shared_names:
        if sname.lower() in name.lower() or name.lower() in sname.lower():
            d1 = min_dist((lat, lon), l1)
            d2 = min_dist((lat, lon), l2)
            print(f'{name:25s}: dist to ETX_PLE={d1:4.1f}m, dist to BSR_KAB={d2:4.1f}m')
            break

