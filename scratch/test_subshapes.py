import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def dist_to_segment(p, a, b):
    mlng = meters_per_deg_lng(p[0])
    ax = (a[1] - p[1]) * mlng
    ay = (a[0] - p[0]) * METERS_PER_DEG_LAT
    bx = (b[1] - p[1]) * mlng
    by = (b[0] - p[0]) * METERS_PER_DEG_LAT
    dx = bx - ax
    dy = by - ay
    len2 = dx * dx + dy * dy
    t = 0.0
    if len2 > 1e-9:
        t = max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
    proj_lat = p[0] + (ay + t * dy) / METERS_PER_DEG_LAT
    proj_lng = p[1] + (ax + t * dx) / mlng
    return dist(p, (proj_lat, proj_lng))

def dist_to_poly(p, poly):
    return min(dist_to_segment(p, poly[i], poly[i+1]) for i in range(len(poly)-1))

# Load shapes
shapes = {}
with open('app/src/main/assets/formas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 4: continue
        sid, lat, lon, seq = p[0].strip(), float(p[1]), float(p[2]), int(p[3])
        if sid not in shapes: shapes[sid] = []
        shapes[sid].append((seq, lat, lon))
for sid in shapes: shapes[sid].sort(key=lambda x: x[0])

etx_ple = [(lat, lon) for seq, lat, lon in shapes['ETX_PLE']]
bsr_kab = [(lat, lon) for seq, lat, lon in shapes['BSR_KAB']]

# For every shape in Line 1, check distance to ETX_PLE
l1_shapes = ['BID_ETX', 'BSR_IBB', 'BSR_PLE', 'BSR_SOP', 'ETX_BID', 'ETX_IBB', 'ETX_LAR', 'ETX_PLE', 'ETX_SOP', 'ETX_URD', 'IBB_ETX', 'LAR_ETX', 'PLE_BSR', 'PLE_ETX', 'SIN_PLE', 'SOP_BSR', 'SOP_ETX', 'URD_ETX']
l2_shapes = ['ANS_BSR', 'ANS_ETX', 'ANS_KAB', 'AST_BSR', 'BSR_KAB', 'BSR_SIN', 'ETX_KAB', 'KAB_BSR', 'KAB_ETX', 'SIN_BSR', 'SIN_KAB', 'SIN_ETX']

for s in l1_shapes:
    pts = [(lat, lon) for seq, lat, lon in shapes[s]]
    # Filter points within Etxebarri - Plentzia range
    dists = [dist_to_poly(p, etx_ple) for p in pts if p[0] > 43.243]
    if dists:
        print(f'L1 Shape {s:10s}: max dist to ETX_PLE = {max(dists):.2f}m, avg = {sum(dists)/len(dists):.2f}m')

for s in l2_shapes:
    pts = [(lat, lon) for seq, lat, lon in shapes[s]]
    dists = [dist_to_poly(p, bsr_kab) for p in pts]
    if dists:
        print(f'L2 Shape {s:10s}: max dist to BSR_KAB = {max(dists):.2f}m, avg = {sum(dists)/len(dists):.2f}m')

