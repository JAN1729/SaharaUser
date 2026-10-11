import asyncio
import hashlib
import time
from datetime import timedelta
from .domain import ProviderResult


class Broker:
    def __init__(self,providers,timeout=25):
        self.providers=providers
        self.timeout=timeout
        self.pending={}
        self.cache={}
        self.progress={}
        self.finished={}
        self.ready={}
        self.generation=0
        self.capacity=asyncio.Semaphore(4)

    def key(self,request,pairs):
        # These constraints affect planning, not raw provider inventory.
        raw=request.model_dump_json(exclude={'budget_minor','preference','max_transfers','max_walk_m',
            'connection_buffer_minutes','airport_processing_minutes','wheelchair','confirmed_only','wait_for_complete'})
        return str(self.generation)+':'+hashlib.sha256((raw+str([(a.id,b.id) for a,b in pairs])).encode()).hexdigest()

    def _start(self,request,pairs,now,key):
        if key not in self.pending:
            self.progress[key]={}
            self.finished[key]=set()
            self.ready[key]=asyncio.Event()
            self.pending[key]=asyncio.create_task(self._collect(request,pairs,now,key),name=key)
        return self.pending[key]

    async def collect(self,request,pairs,now):
        key=self.key(request,pairs)
        cached=self.cache.get(key)
        if cached and cached[0]>now:
            return [result.model_copy(update={'cached':True}) for result in cached[1]]
        return await asyncio.shield(self._start(request,pairs,now,key))

    def snapshot(self,request,pairs,now):
        return self.snapshot_key(self.key(request,pairs),now)

    def snapshot_key(self,key,now):
        cached=self.cache.get(key)
        if cached and cached[0]>now:
            return [r.model_copy(update={'cached':True}) for r in cached[1]]
        values=self.progress.get(key,{})
        return [values.get(p.name) or ProviderResult(provider=p.name,status='partial',
            message='Source check still running; poll the stored search for updates') for p in self.providers]

    def pending_names(self,request,pairs):
        return self.pending_names_key(self.key(request,pairs))

    def pending_names_key(self,key):
        return [p.name for p in self.providers if p.name not in self.finished.get(key,set())] if key in self.pending else []

    async def collect_fast(self,request,pairs,now,timeout=4):
        key=self.key(request,pairs)
        cached=self.cache.get(key)
        if cached and cached[0]>now:
            return [r.model_copy(update={'cached':True}) for r in cached[1]],None
        task=self._start(request,pairs,now,key)
        waiter=asyncio.create_task(self.ready[key].wait())
        try:
            await asyncio.wait({task,waiter},timeout=timeout,return_when=asyncio.FIRST_COMPLETED)
        finally:
            waiter.cancel()
            await asyncio.gather(waiter,return_exceptions=True)
        if task.done():
            return task.result(),None
        return self.snapshot(request,pairs,now),task

    async def _collect(self,request,pairs,now,key):
        started=time.monotonic()
        async def run(provider):
            def update(result):
                self.progress[key][provider.name]=result
                if result.legs:
                    self.ready[key].set()
            async def call():
                async with self.capacity:
                    if hasattr(provider,'search_progressive'):
                        return await provider.search_progressive(request,pairs,now,update)
                    return await provider.search(request,pairs,now)
            try:
                result=await asyncio.wait_for(call(),self.timeout)
            except TimeoutError:
                previous=self.progress[key].get(provider.name)
                result=ProviderResult(provider=provider.name,status='partial' if previous and previous.legs else 'timeout',
                    legs=previous.legs if previous else [],message='Provider search deadline exceeded; partial inventory retained' if previous and previous.legs else 'Provider search deadline exceeded')
            except Exception as error:
                previous=self.progress[key].get(provider.name)
                result=ProviderResult(provider=provider.name,status='partial' if previous and previous.legs else 'error',
                    legs=previous.legs if previous else [],message=type(error).__name__+': collection incomplete')
            update(result)
            self.finished[key].add(provider.name)
            return result
        try:
            results=await asyncio.gather(*(run(p) for p in self.providers))
            expiries=[leg.observation.expires_at for result in results for leg in result.legs if leg.observation.expires_at]
            completed=now+timedelta(seconds=time.monotonic()-started)
            until=min([completed+timedelta(minutes=2),*expiries])
            if any(r.status in ('error','timeout','partial') for r in results):
                until=min(until,completed+timedelta(seconds=15))
            if len(self.cache)>200:
                self.cache.clear()
                for old in list(self.progress):
                    if old not in self.pending:
                        self.progress.pop(old,None)
                        self.finished.pop(old,None)
                        self.ready.pop(old,None)
            if key.startswith(str(self.generation)+':'):
                self.cache[key]=(until,results)
            return results
        finally:
            self.pending.pop(key,None)
            if key not in self.cache:
                self.progress.pop(key,None)
                self.finished.pop(key,None)
                self.ready.pop(key,None)

    def invalidate(self):
        self.generation+=1
        self.cache.clear()
        for key in list(self.progress):
            if key not in self.pending:
                self.progress.pop(key,None)
                self.finished.pop(key,None)
                self.ready.pop(key,None)

    async def aclose(self):
        tasks=list(self.pending.values())
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
