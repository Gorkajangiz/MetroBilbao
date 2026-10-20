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
    if tlen < 1e-9: return p
    tx /= tlen
    ty /= tlen
    nx = -ty
    ny = tx
    return (p[0] + (ny * meters) / METERS_PER_DEG_LAT,
            p[1] + (nx * meters) / mlng)

# Suppose we have a master Line 1 polyline (ETX_PLE) and master Line 2 polyline (BSR_KAB)
# What if any trip on Line 1 projects its stops onto the master Line 1 polyline,
# OR what if the reverse shape uses -meters?
print('Check normal direction:')
p1 = (43.26, -2.93)
p2 = (43.27, -2.94) # moving NW
off_fwd = offset_point(p1, p1, p2, 7.0)
off_rev = offset_point(p1, p2, p1, -7.0)
print('Fwd with +7:', off_fwd)
print('Rev with -7:', off_rev)
print('Diff between them:', dist(off_fwd, off_rev))

