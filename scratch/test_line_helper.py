def determinar_linea(shape_id, headsign):
    if shape_id is None: return "L1"
    if any(k in shape_id for k in ['PLE', 'SOP', 'URD', 'IBB', 'BID', 'LAR']):
        return "L1"
    if any(k in shape_id for k in ['KAB', 'ANS']):
        return "L2"
    if headsign:
        hs = headsign.lower()
        if any(k in hs for k in ['plentzia', 'sopela', 'larrabasterra', 'bidezabal', 'ibarbengoa', 'urduliz']):
            return "L1"
        if any(k in hs for k in ['kabiezes', 'santurtzi']):
            return "L2"
    if 'BSR' in shape_id:
        return "L2"
    return "L1"

with open('app/src/main/assets/viajes.txt', encoding='utf-8') as f:
    f.readline()
    trips = {}
    for line in f:
        p = line.strip().split(',')
        if len(p) >= 6:
            trips[p[0].strip()] = (p[3].strip(), p[5].strip())

l1_count = 0
l2_count = 0
for tid, (hs, sh) in trips.items():
    l = determinar_linea(sh, hs)
    if l == 'L1': l1_count += 1
    else: l2_count += 1

print(f'Total trips: {len(trips)} -> L1: {l1_count}, L2: {l2_count}')

