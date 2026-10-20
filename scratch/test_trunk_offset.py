import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

# Let's test the shared trunk bounds:
# San Inazio: lat ~ 43.284, lon ~ -2.966
# Etxebarri: lat ~ 43.244, lon ~ -2.897
# If a point is in the shared corridor, how close is it to the other line?
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

def min_dist_to_line(p, line_pts):
    return min(dist(p, q) for q in line_pts)

def get_offset_for_point(p, other_line_pts, sign_meters=8.0):
    d = min_dist_to_line(p, other_line_pts)
    threshold = 80.0
    if d >= threshold:
        return 0.0, 0.0 # No offset outside parallel section
    # Smooth factor 1.0 when d < 30, tapering to 0.0 at 80m
    t = max(0.0, min(1.0, 1.0 - d / threshold))
    factor = t * t * (3.0 - 2.0 * t)
    # Direction perpendicular to Bilbao trunk (roughly perpendicular to SE direction, i.e. heading ~30 deg)
    # A fixed unit vector pointing ~30 deg North-East:
    # dx (lng) = sin(30) * m = 0.5
    # dy (lat) = cos(30) * m = 0.866
    offset_m = sign_meters * factor
    dlat = (0.866 * offset_m) / METERS_PER_DEG_LAT
    dlng = (0.5 * offset_m) / meters_per_deg_lng(p[0])
    return dlat, dlng

# Test on Moya (in center) and Plentzia (far north)
moya = (43.26259, -2.93308)
plentzia = (43.40175, -2.94638)
kabiezes = (43.32833, -3.03333)

print('Moyua offset L1:', get_offset_for_point(moya, bsr_kab, 8.0))
print('Moyua offset L2:', get_offset_for_point(moya, etx_ple, -8.0))
print('Plentzia offset L1:', get_offset_for_point(plentzia, bsr_kab, 8.0))
print('Kabiezes offset L2:', get_offset_for_point(kabiezes, etx_ple, -8.0))

