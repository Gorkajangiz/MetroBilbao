import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def offset_point(p, prev_p, next_p, meters):
    mlng = meters_per_deg_lng(p[0])
    tx = (next_p[1] - prev_p[1]) * mlng
    ty = (next_p[0] - prev_p[0]) * METERS_PER_DEG_LAT
    tlen = math.sqrt(tx * tx + ty * ty)
    if tlen < 1e-9:
        return p
    tx /= tlen
    ty /= tlen
    # Normal perpendicular (left hand side if meters > 0)
    nx = -ty
    ny = tx
    return (p[0] + (ny * meters) / METERS_PER_DEG_LAT,
            p[1] + (nx * meters) / mlng)

def offset_polyline(points, meters):
    n = len(points)
    if n < 2: return list(points)
    res = []
    for i in range(n):
        if i == 0:
            prev_p = points[0]
            next_p = points[1]
        elif i == n - 1:
            prev_p = points[n - 2]
            next_p = points[n - 1]
        else:
            prev_p = points[i - 1]
            next_p = points[i + 1]
        res.append(offset_point(points[i], prev_p, next_p, meters))
    return res

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

off_l1 = offset_polyline(etx_ple, 7.0)
off_l2 = offset_polyline(bsr_kab, -7.0)

print(f'Original L1 pts: {len(etx_ple)}, Offset L1 pts: {len(off_l1)}')
print(f'Original L2 pts: {len(bsr_kab)}, Offset L2 pts: {len(off_l2)}')
print(f'Sample L1 orig: {etx_ple[100]} -> offset: {off_l1[100]}')
print(f'Distance: {dist(etx_ple[100], off_l1[100]):.2f}m')

