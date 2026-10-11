"""Build a bounded directed bus-road graph from local public OSM extracts."""
import argparse
import hashlib
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
import osmium
from sahara.places import TERMINALS
from sahara.geometry import Graph

HIGHWAYS={'motorway','motorway_link','trunk','trunk_link','primary','primary_link','secondary','secondary_link','tertiary','tertiary_link'}


def metres(a,b):
    p,q=math.radians(a[0]),math.radians(b[0])
    h=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(math.radians(b[1]-a[1])/2)**2
    return 6371000*2*math.asin(math.sqrt(min(1,h)))


def driveable(tags):
    if tags.get('highway') not in HIGHWAYS or tags.get('area')=='yes' or tags.get('bus')=='no': return False
    for field in ('access','vehicle','motor_vehicle'):
        if tags.get(field) in ('no','private') and tags.get('bus') not in ('yes','designated') and tags.get('psv') not in ('yes','designated'): return False
    return True


class RoadReader(osmium.SimpleHandler):
    def __init__(self,ways,coordinates):
        super().__init__()
        self.ways,self.coordinates=ways,coordinates

    def way(self,way):
        tags=dict(way.tags)
        if not driveable(tags): return
        # Dataset profile: major southern roads; no claim of complete local-road coverage.
        nodes=[]
        for item in way.nodes:
            if not item.location.valid(): return
            point=(item.lat,item.lon)
            if not (7.5<=point[0]<=17 and 73.5<=point[1]<=81): return
            nodes.append(item.ref)
            self.coordinates[item.ref]=point
            if len(self.coordinates)>3000000: raise ValueError('Road extraction exceeds bounded size')
        if len(nodes)<2: return
        old=self.ways.get(way.id)
        if old and len(old[0])>=len(nodes): return
        reverse=tags.get('oneway')=='-1'
        if reverse: nodes.reverse()
        oneway=reverse or tags.get('oneway') in ('yes','true','1') or (tags.get('junction')=='roundabout' and tags.get('oneway')!='no')
        self.ways[way.id]=(nodes,not oneway)
        if len(self.ways)>100000: raise ValueError('Road extraction exceeds bounded size')


def build(paths):
    ways,coordinates={},{}
    sources=[]
    for path in paths:
        if path.stat().st_size>250_000_000: raise ValueError('OSM source file exceeds bounded size')
        meta=path.with_suffix(path.suffix+'.source.json')
        if not meta.exists(): raise ValueError('OSM source provenance metadata is required')
        source=json.loads(meta.read_text(encoding='utf-8'))
        if hashlib.sha256(path.read_bytes()).hexdigest()!=source['sha256']: raise ValueError('OSM source hash mismatch')
        sources.append(source)
        print('Reading '+path.name,flush=True)
        RoadReader(ways,coordinates).apply_file(str(path),locations=True,idx='flex_mem')
        print(f'Road ways {len(ways)}, road shape points {len(coordinates)}',flush=True)
        if len(ways)>100000 or len(coordinates)>3000000: raise ValueError('Road extraction exceeds bounded size')
    use=Counter(node for nodes,_ in ways.values() for node in nodes)
    junctions={node for node,count in use.items() if count>1}
    for nodes,_ in ways.values(): junctions.update((nodes[0],nodes[-1]))
    grid=defaultdict(list)
    for node,point in coordinates.items(): grid[(int(point[0]*100),int(point[1]*100))].append(node)
    terminal_nodes,snaps={},{}
    for id,terminal in TERMINALS.items():
        x,y=int(terminal.lat*100),int(terminal.lng*100)
        nearby=[node for a in range(x-1,x+2) for b in range(y-1,y+2) for node in grid.get((a,b),[])]
        if not nearby: continue
        point=(terminal.lat,terminal.lng)
        node=min(nearby,key=lambda n:metres(point,coordinates[n]))
        snap=metres(point,coordinates[node])
        if snap<=500:
            junctions.add(node)
            terminal_nodes[id]=str(node)
            snaps[id]=round(snap,2)
    nodes={str(node):{'lat':round(coordinates[node][0],7),'lng':round(coordinates[node][1],7)} for node in junctions}
    edges=[]
    for ids,both in ways.values():
        start=0
        for index in range(1,len(ids)):
            if ids[index] not in junctions: continue
            part=ids[start:index+1]
            start=index
            points=[coordinates[n] for n in part]
            length=sum(metres(a,b) for a,b in zip(points,points[1:]))
            if length<=0 or part[0]==part[-1]: continue
            # Preserve source way vertices and directions; never interpolate a corridor.
            edges.append({'origin':str(part[0]),'destination':str(part[-1]),'length_m':round(length,2),
                'mode':'road','bidirectional':both,'polyline':[{'lat':round(p[0],7),'lng':round(p[1],7)} for p in points]})
    if len(nodes)>100000 or len(edges)>200000: raise ValueError('Compressed graph exceeds runtime limits')
    revision='osm-south-'+hashlib.sha256(json.dumps(sources,sort_keys=True).encode()).hexdigest()[:12]
    return {'source_type':'road_graph','source_url':'https://download.openstreetmap.fr/extracts/asia/india/',
        'revision':revision,'nodes':nodes,'edges':edges,'terminal_nodes':terminal_nodes,'terminal_snap_m':snaps},sources


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('extracts',type=Path,nargs='+')
    parser.add_argument('--output',type=Path,default=Path('data/osm-road-graph.json'))
    args=parser.parse_args()
    graph,sources=build(args.extracts)
    # Reject disconnected endpoints, sparse shapes and invalid distances before publication.
    Graph.model_validate(graph)
    raw=json.dumps(graph,separators=(',',':'))
    if len(raw.encode())>128_000_000: raise ValueError('Graph file exceeds runtime limit')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(raw,encoding='utf-8')
    args.output.with_suffix('.sources.json').write_text(json.dumps({'sources':sources,'license':'OpenStreetMap ODbL',
        'notice':'Calculated major-road guidance only; bus operator path and traffic are unverified.'},indent=2),encoding='utf-8')
    print(json.dumps({'nodes':len(graph['nodes']),'edges':len(graph['edges']),'terminal_bindings':graph['terminal_nodes'],
        'bytes':len(raw.encode()),'output':str(args.output)},indent=2))


if __name__=='__main__': main()
