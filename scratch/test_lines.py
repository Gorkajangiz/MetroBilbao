# Check how trips map to Line 1 vs Line 2
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

shapes_l1 = set()
shapes_l2 = set()

# In Metro Bilbao:
# Line 1 terminates/runs to: Plentzia, Urduliz, Sopela, Larrabasterra, Bidezabal, Ibarbengoa (Right Bank)
# Line 2 terminates/runs to: Kabiezes, Santurtzi (Left Bank) and Basauri (South)
for tid, m in trips.items():
    sh = m['shape']
    hs = m['headsign']
    if any(k in sh for k in ['KAB', 'ANS', 'BSR']) and not ('PLE' in sh or 'SOP' in sh or 'URD' in sh or 'IBB' in sh or 'BID' in sh or 'LAR' in sh):
        shapes_l2.add(sh)
    elif any(k in sh for k in ['PLE', 'SOP', 'URD', 'IBB', 'BID', 'LAR']):
        shapes_l1.add(sh)
    else:
        pass

print('Identified L1 shapes:', sorted(shapes_l1))
print('Identified L2 shapes:', sorted(shapes_l2))

