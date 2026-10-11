import unittest
import tempfile
from pathlib import Path
from datetime import datetime,timedelta,timezone
from sahara.domain import Leg,Observation,SearchRequest,ProviderResult,Mode
from sahara.service import PlanningService
from sahara.settings import Settings


class FixedBroker:
    def __init__(self,legs): self.legs=legs
    def invalidate(self): pass
    async def collect(self,*args):
        return [ProviderResult(provider='test',status='ok',legs=self.legs)]


class RevalidationReviewTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder=tempfile.TemporaryDirectory()
        self.service=PlanningService(Settings(database=Path(self.folder.name)/'db'))
        self.now=datetime.now(timezone.utc)
        obs=Observation(provider='test',evidence='live',fetched_at=self.now,observed_at=self.now,expires_at=self.now+timedelta(hours=1))
        self.legs=[Leg(id='l1',provider='test',service_id='train1',name='First',mode='train',origin='kty',destination='ers',
            departure=self.now-timedelta(minutes=15),arrival=self.now+timedelta(hours=1),fare_minor=10000,availability='available',observation=obs),
            Leg(id='l2',provider='test',service_id='train2',name='Second',mode='train',origin='ers',destination='sbc',
            departure=self.now+timedelta(hours=2),arrival=self.now+timedelta(hours=5),fare_minor=50000,availability='available',observation=obs)]
        self.service.live=FixedBroker(self.legs)
        request=SearchRequest(origin='kty',destination='sbc',depart_after=self.now-timedelta(minutes=20),data_mode='live')
        result=await self.service.search(request,'owner',self.now-timedelta(hours=1))
        self.id=result['plan']['journeys'][0]['id']

    async def asyncTearDown(self): self.folder.cleanup()

    async def test_started_journey_requests_progress_instead_of_stopping(self):
        result=await self.service.revalidate(self.id,'owner')
        self.assertTrue(result['requires_progress'])
        self.assertFalse(result['original_valid'])
        self.assertEqual([],result['alternatives'])

    async def test_onboard_leg_is_preserved_and_delay_changes_onward_options(self):
        self.service.live.legs=[self.legs[0].model_copy(update={'estimated_arrival':self.now+timedelta(hours=3)}),self.legs[1]]
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertFalse(result['requires_progress'])
        self.assertFalse(result['original_valid'])
        self.assertEqual([],result['alternatives'])

    async def test_onboard_progress_survives_subsequent_checks(self):
        first=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        second=await self.service.revalidate(self.id,'owner')
        self.assertTrue(first['original_valid'])
        self.assertTrue(second['original_valid'])
        self.assertEqual('l1',second['current_journey']['legs'][0]['id'])

    async def test_progress_must_be_a_contiguous_prefix(self):
        with self.assertRaises(ValueError):
            await self.service.revalidate(self.id,'owner',completed_leg_ids=['l2'])

    async def test_stale_confirmation_cannot_regress_onboard_or_completed_progress(self):
        await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        with self.assertRaisesRegex(ValueError,'already advanced'):
            await self.service.revalidate(self.id,'owner',completed_leg_ids=[])
        await self.service.revalidate(self.id,'owner',completed_leg_ids=['l1'])
        with self.assertRaisesRegex(ValueError,'already advanced'):
            await self.service.revalidate(self.id,'owner',completed_leg_ids=[],onboard_leg_id='l1')
        saved=self.service.repository.get('journey',self.id,'owner')['progress']
        self.assertEqual(['l1'],saved['completed_leg_ids'])
        self.assertIsNone(saved['onboard_leg_id'])

    async def test_onboard_final_leg_remains_valid_including_egress(self):
        for destination in ['sbc','bengaluru-bus']:
            with self.subTest(destination=destination):
                direct=self.legs[0].model_copy(update={'destination':destination,'mode':Mode.LOCAL})
                self.service.live=FixedBroker([direct])
                request=SearchRequest(origin='kty',destination='sbc',depart_after=self.now-timedelta(minutes=20),data_mode='live')
                record=await self.service.search(request,'owner',self.now-timedelta(hours=1))
                result=await self.service.revalidate(record['plan']['journeys'][0]['id'],'owner',onboard_leg_id='l1')
                self.assertTrue(result['original_valid'])
                expected=direct.end+timedelta(minutes=10 if destination=='bengaluru-bus' else 0)
                self.assertEqual(expected,datetime.fromisoformat(result['current_journey']['arrival']))

    async def test_recovery_anchors_future_dates_after_a_long_delay(self):
        row=self.service.repository.get('journey',self.id,'owner')
        row['request']['depart_after']=(self.now-timedelta(days=3,minutes=20)).isoformat()
        for leg in row['journey']['legs']:
            for key in ('departure','arrival'):
                leg[key]=(datetime.fromisoformat(leg[key])-timedelta(days=3)).isoformat()
        self.service.repository.save('journey',self.id,'owner',row)
        shifted=self.legs[0].model_copy(update={'departure':self.legs[0].departure-timedelta(days=3),
            'arrival':self.legs[0].arrival-timedelta(days=3),'estimated_arrival':self.now+timedelta(hours=1)})
        base=self
        class DateBroker(FixedBroker):
            async def collect(self,request,*args):
                base.collected_date=request.depart_after.date()
                return [ProviderResult(provider='test',status='ok',legs=self.legs if request.depart_after.date()==base.now.date() else [shifted])]
        self.service.live=DateBroker([shifted,self.legs[1]])
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertEqual(self.now.date(),self.collected_date)
        self.assertTrue(result['alternatives'])

    async def test_refreshed_future_departure_conflicts_with_onboard_progress(self):
        self.service.live.legs=[self.legs[0].model_copy(update={'departure':self.now+timedelta(minutes=30)}),self.legs[1]]
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertTrue(result['requires_progress'])
        self.assertEqual([],result['alternatives'])

    async def test_recovery_does_not_extend_an_explicit_elapsed_deadline(self):
        row=self.service.repository.get('journey',self.id,'owner')
        row['request']['depart_after']=(self.now-timedelta(days=3,minutes=20)).isoformat()
        row['request']['arrive_by']=(self.now-timedelta(days=2)).isoformat()
        for leg in row['journey']['legs']:
            for key in ('departure','arrival'):
                leg[key]=(datetime.fromisoformat(leg[key])-timedelta(days=3)).isoformat()
        self.service.repository.save('journey',self.id,'owner',row)
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertFalse(result['original_valid'])
        self.assertEqual([],result['alternatives'])
        self.assertIn('deadline',result['message'].lower())

    async def test_onboard_leg_keeps_original_fare_when_fresh_offer_changes(self):
        self.service.live.legs=[self.legs[0].model_copy(update={'fare_minor':90000}),self.legs[1]]
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertEqual(60000,result['current_journey']['total_fare_minor'])

    async def test_old_onboard_arrival_requires_updated_progress(self):
        row=self.service.repository.get('journey',self.id,'owner')
        row['journey']['legs'][0]['arrival']=(self.now-timedelta(minutes=1)).isoformat()
        self.service.repository.save('journey',self.id,'owner',row)
        self.service.live.legs=[self.legs[0].model_copy(update={'arrival':self.now-timedelta(minutes=1)}),self.legs[1]]
        result=await self.service.revalidate(self.id,'owner',onboard_leg_id='l1')
        self.assertTrue(result['requires_progress'])
        self.assertEqual([],result['alternatives'])

    async def test_route_identity_is_required_for_original_match(self):
        future=[l.model_copy(update={'departure':l.departure+timedelta(days=1),'arrival':l.arrival+timedelta(days=1)}) for l in self.legs]
        self.service.live=FixedBroker(future)
        result=await self.service.search(SearchRequest(origin='kty',destination='sbc',depart_after=self.now+timedelta(hours=12),data_mode='live'),'owner')
        chosen=result['plan']['journeys'][0]['id']
        # Same provider/train/date/class tuple, different segment endpoints.
        self.service.live.legs=[future[0].model_copy(update={'destination':'kottayam-bus'}),future[1].model_copy(update={'origin':'kottayam-bus'})]
        refreshed=await self.service.revalidate(chosen,'owner')
        self.assertFalse(refreshed['original_valid'])


class CoverageReviewTests(unittest.IsolatedAsyncioTestCase):
    async def test_truncated_dates_are_exposed_in_provider_status(self):
        from sahara.providers.rail import RailProvider
        from sahara.places import TERMINALS
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='ers',destination='sbc',depart_after=datetime(2026,10,10,23,tzinfo=timezone(timedelta(hours=5,minutes=30))))
        async def collect(*args):
            return '''<div class="train-card"><div><span class="mr-5">12677</span>Express</div>
            <p>23:00 ERS</p><p>02:00 SBC</p><p>3h 0m</p><div class="cache-card-wrapper">
            <p>SL</p><p>₹450</p><p>Available 4</p><p>now</p></div></div>'''
        result=await RailProvider(collector=collect).search(request,[(TERMINALS['ers'],TERMINALS['sbc'])],now)
        self.assertEqual('partial',result.status)
        self.assertIn('date',result.message.lower())
