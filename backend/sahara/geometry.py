"""Map guidance from configured source geometry, never a line invented by an LLM."""
import heapq
import json
import math
import copy
from collections import OrderedDict
from pathlib import Path
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class Point(BaseModel):
    model_config=ConfigDict(extra='forbid',allow_inf_nan=False)
    lat:float=Field(ge=-90,le=90)
    lng:float=Field(ge=-180,le=180)


def distance(a,b):
    p,q=math.radians(a.lat),math.radians(b.lat)
    x=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(math.radians(b.lng-a.lng)/2)**2
    return 6371000*2*math.atan2(math.sqrt(x),math.sqrt(max(0,1-x)))


class Edge(BaseModel):
    model_config=ConfigDict(extra='forbid')
    origin:str
    destination:str
    length_m:float=Field(gt=0)
    mode:Literal['road','train','metro','walk','ferry']
    polyline:list[Point]=Field(min_length=2,max_length=10000)
    bidirectional:bool=False
    service_routes:list[str]=Field(default_factory=list,max_length=1000)


class Graph(BaseModel):
    model_config=ConfigDict(extra='forbid')
    source_type:Literal['road_graph','operator_geometry']
    source_url:str=Field(min_length=1,max_length=1000)
    revision:str=Field(min_length=1,max_length=100)
    nodes:dict[str,Point]=Field(max_length=100000)
    edges:list[Edge]=Field(max_length=200000)
    terminal_nodes:dict[str,str]=Field(default_factory=dict,max_length=50)
    terminal_snap_m:dict[str,float]=Field(default_factory=dict,max_length=50)
    @model_validator(mode='after')
    def connected_shapes(self):
        if set(self.terminal_nodes)!=set(self.terminal_snap_m): raise ValueError('Terminal bindings require snap distances')
        for terminal,node in self.terminal_nodes.items():
            snap=self.terminal_snap_m[terminal]
            if node not in self.nodes or not math.isfinite(snap) or not 0<=snap<=500:
                raise ValueError('Invalid terminal road binding')
        for edge in self.edges:
            if edge.origin not in self.nodes or edge.destination not in self.nodes:
                raise ValueError('Graph edge references an unknown node')
            if distance(self.nodes[edge.origin],edge.polyline[0])>20 or distance(self.nodes[edge.destination],edge.polyline[-1])>20:
                raise ValueError('Geometry endpoints disagree with graph nodes')
            spans=[distance(a,b) for a,b in zip(edge.polyline,edge.polyline[1:])]
            if any(span>5000 for span in spans): raise ValueError('Source geometry is too sparse to use as route guidance')
            actual=sum(spans)
            if not .8*actual<=edge.length_m<=1.5*actual or actual<=0:
                raise ValueError('Edge distance disagrees with source geometry')
            # Route choice and displayed distance use source shape measurement.
            edge.length_m=actual
        return self


class GeometryRouter:
    def __init__(self,graph=None):
        self.graph=Graph.model_validate(graph) if graph else None
        self.adjacency={}
        self.cache=OrderedDict()
        if self.graph:
            for edge in self.graph.edges:
                self.adjacency.setdefault(edge.origin,[]).append(edge)
                if edge.bidirectional:
                    self.adjacency.setdefault(edge.destination,[]).append(edge.model_copy(update={
                        'origin':edge.destination,'destination':edge.origin,'polyline':list(reversed(edge.polyline))}))
    @classmethod
    def from_path(cls,path):
        if not path: return cls()
        file=Path(path)
        if file.stat().st_size>128000000: raise ValueError('Route graph exceeds configured size limit')
        return cls(json.loads(file.read_text(encoding='utf-8')))

    def route(self,origin,destination,mode,required_service=None):
        missing={'status':'unavailable','polyline':[],'distance_m':None,
            'notice':'No mapped source geometry for this segment. Endpoint pins are not a road or rail route.'}
        if not self.graph: return missing
        origin_id,destination_id=origin,destination
        origin=self.graph.terminal_nodes.get(origin,origin)
        destination=self.graph.terminal_nodes.get(destination,destination)
        if origin not in self.graph.nodes or destination not in self.graph.nodes: return missing
        key=(origin_id,destination_id,mode,required_service)
        if key in self.cache:
            self.cache.move_to_end(key)
            return copy.deepcopy(self.cache[key])
        network='road' if mode in ('bus','auto','private','local') else mode
        queue=[(0,origin)]
        best={origin:0}
        previous={}
        while queue:
            cost,node=heapq.heappop(queue)
            if cost!=best.get(node): continue
            if node==destination and node in previous:
                path=[]
                cursor=destination
                while cursor!=origin:
                    edge=previous[cursor]
                    path.append(edge)
                    cursor=edge.origin
                path.reverse()
                shape=[]
                for edge in path:
                    points=[p.model_dump() for p in edge.polyline]
                    shape.extend(points[1:] if shape and shape[-1]==points[0] else points)
                result={'status':'mapped','polyline':shape,'distance_m':round(cost),
                    'source_url':self.graph.source_url,'revision':self.graph.revision,
                    'algorithm':'dijkstra','evidence':'configured_source_geometry',
                    'origin_snap_m':self.graph.terminal_snap_m.get(origin_id,0),
                    'destination_snap_m':self.graph.terminal_snap_m.get(destination_id,0),
                    'notice':'Navigation guidance from this dataset; service path and current traffic are not certified.'}
                self.cache[key]=copy.deepcopy(result)
                while len(self.cache)>64: self.cache.popitem(last=False)
                return result
            for edge in self.adjacency.get(node,[]):
                candidate=cost+edge.length_m
                if edge.mode==network and (required_service is None or required_service in edge.service_routes) and candidate<best.get(edge.destination,float('inf')):
                    best[edge.destination]=candidate
                    previous[edge.destination]=edge
                    heapq.heappush(queue,(candidate,edge.destination))
        return missing

    def bus_guidance(self,origin,destination,service_key):
        route=self.route(origin,destination,'bus',required_service=service_key or '__unmapped_service__')
        if route['status']=='mapped': return dict(route,service_path_verified=True)
        if self.graph is None or self.graph.source_type!='road_graph': return route
        guide=self.route(origin,destination,'bus')
        if guide['status']!='mapped': return route
        return dict(guide,status='road_guidance',evidence='calculated_road_corridor',service_path_verified=False,
            notice='Calculated road guidance from OpenStreetMap. The operator bus path, exact boarding bay, road restrictions and current traffic are unverified. Dashed line is guidance, not live vehicle tracking.')
