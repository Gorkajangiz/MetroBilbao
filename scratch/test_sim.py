import math

METERS_PER_DEG_LAT = 111320.0
def meters_per_deg_lng(lat):
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat))

def dist(p1, p2):
    mlng = meters_per_deg_lng((p1[0] + p2[0]) * 0.5)
    dlat = (p2[0] - p1[0]) * METERS_PER_DEG_LAT
    dlng = (p2[1] - p1[1]) * mlng
    return math.sqrt(dlat * dlat + dlng * dlng)

def project_point_on_segment(p, a, b):
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
    return (p[0] + (ay + t * dy) / METERS_PER_DEG_LAT,
            p[1] + (ax + t * dx) / mlng, t)

class ShapePathPy:
    def __init__(self, pts):
        self.pts = pts
        self.cum_dist = [0.0] * len(pts)
        for i in range(1, len(pts)):
            self.cum_dist[i] = self.cum_dist[i-1] + dist(pts[i-1], pts[i])
        self.total_length = self.cum_dist[-1]

    def project_offset(self, p, hint_offset=-1.0, max_search=0.0):
        n = len(self.pts) - 1
        if n < 1: return 0.0
        hint_valid = max_search > 0 and 0 <= hint_offset <= self.total_length
        best_dist = float('inf')
        chosen = 0.0
        found = False

        if hint_valid:
            lower = max(0.0, hint_offset - max_search)
            upper = min(self.total_length, hint_offset + max_search)
            for i in range(n):
                if self.cum_dist[i+1] < lower or self.cum_dist[i] > upper:
                    continue
                found = True
                pr = project_point_on_segment(p, self.pts[i], self.pts[i+1])
                d = dist(p, (pr[0], pr[1]))
                if d < best_dist:
                    best_dist = d
                    chosen = self.cum_dist[i] + pr[2] * (self.cum_dist[i+1] - self.cum_dist[i])

        if not found or best_dist > 250.0:
            best_dist = float('inf')
            for i in range(n):
                pr = project_point_on_segment(p, self.pts[i], self.pts[i+1])
                d = dist(p, (pr[0], pr[1]))
                if d < best_dist:
                    best_dist = d
                    chosen = self.cum_dist[i] + pr[2] * (self.cum_dist[i+1] - self.cum_dist[i])
        return chosen

    def point_at(self, offset):
        if offset <= 0: return self.pts[0]
        if offset >= self.total_length: return self.pts[-1]
        lo, hi = 0, len(self.pts) - 1
        while lo < hi - 1:
            mid = (lo + hi) >> 1
            if self.cum_dist[mid] <= offset: lo = mid
            else: hi = mid
        seg_start = self.cum_dist[lo]
        seg_len = self.cum_dist[lo+1] - seg_start
        t = (offset - seg_start) / seg_len if seg_len > 0 else 0.0
        a, b = self.pts[lo], self.pts[lo+1]
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

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

shapes_obj = {}
for sid, pts in shapes.items():
    pts.sort(key=lambda x: x[0])
    shapes_obj[sid] = ShapePathPy([(lat, lon) for seq, lat, lon in pts])

# Load paradas
stops = {}
with open('app/src/main/assets/paradas.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 5:
            stops[p[0].strip()] = (p[2].strip(), float(p[3]), float(p[4]))

# Load viajes
trips = {}
with open('app/src/main/assets/viajes.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 6:
            trips[p[0].strip()] = {
                'route': p[1].strip(),
                'service': p[2].strip(),
                'headsign': p[3].strip(),
                'dir': p[4].strip(),
                'shape': p[5].strip()
            }

# Load stop times
def parse_time(s):
    h, m, sec = map(int, s.split(':'))
    return h * 3600 + m * 60 + sec

horarios = {}
with open('app/src/main/assets/horarios.txt', encoding='utf-8') as f:
    f.readline()
    for line in f:
        p = line.strip().split(',')
        if len(p) < 5: continue
        tid = p[0].strip()
        seq = int(p[1].strip())
        arr = parse_time(p[2].strip())
        dep = parse_time(p[3].strip())
        sid = p[4].strip()
        if tid not in horarios: horarios[tid] = []
        horarios[tid].append({'seq': seq, 'arr': arr, 'dep': dep, 'sid': sid})

for tid in horarios: horarios[tid].sort(key=lambda x: x['seq'])

# Precompute stop offsets for service obranegvia2_invl_26.pex
service = 'obranegvia2_invl_26.pex'
trips_in_service = {tid: m for tid, m in trips.items() if m['service'] == service}
print(f'Trips in {service}: {len(trips_in_service)}')

precomputed_trips = {}
for tid, meta in trips_in_service.items():
    sh_id = meta['shape']
    path = shapes_obj.get(sh_id)
    if not path or tid not in horarios: continue
    st = horarios[tid]
    offsets = []
    last_off = -1.0
    for s in st:
        spos = stops.get(s['sid'])
        if not spos:
            offsets.append(last_off if last_off >= 0 else 0.0)
            continue
        off = path.project_offset((spos[1], spos[2]), last_off, 4000.0 if last_off >= 0 else 0.0)
        offsets.append(off)
        last_off = off
    precomputed_trips[tid] = {
        'meta': meta,
        'path': path,
        'stops': st,
        'offsets': offsets,
        'startSec': st[0]['arr'],
        'endSec': st[-1]['dep']
    }

print(f'Successfully precomputed {len(precomputed_trips)} trips!')

# Test simulation at 13:30:00 to 13:30:10 with 1 second step
now = 13 * 3600 + 30 * 60
print(f'\n--- Simulating 10 seconds at {now} (13:30:00) ---')
for t in range(now, now + 5):
    active_count = 0
    in_station_count = 0
    in_motion_count = 0
    for tid, data in precomputed_trips.items():
        if data['startSec'] <= t <= data['endSec']:
            active_count += 1
            st = data['stops']
            offs = data['offsets']
            # Find status
            for i in range(len(st)):
                if st[i]['arr'] <= t <= st[i]['dep']:
                    in_station_count += 1
                    break
                if i < len(st) - 1 and st[i]['dep'] < t < st[i+1]['arr']:
                    in_motion_count += 1
                    break
    print(f'Time {t} ({t//3600:02d}:{(t%3600)//60:02d}:{t%60:02d}) -> Active: {active_count} (In Station: {in_station_count}, Moving: {in_motion_count})')

