import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event
from sahara.storage import Repository


class AtomicProgressTests(unittest.TestCase):
    def test_other_repository_cannot_commit_a_stale_shorter_confirmation(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.db'
            first,second=Repository(path),Repository(path)
            initial={'completed_leg_ids':[],'onboard_leg_id':None}
            advanced={'completed_leg_ids':['a'],'onboard_leg_id':'b'}
            first.save('journey','j','owner',{'progress':initial,'metadata':'preserved'})
            committed=Event()
            def advance():
                second.update_progress('j','owner',initial,advanced)
                committed.set()
            def stale():
                self.assertTrue(committed.wait(5))
                with self.assertRaisesRegex(ValueError,'changed during'):
                    first.update_progress('j','owner',initial,{'completed_leg_ids':[],'onboard_leg_id':'a'})
            with ThreadPoolExecutor(max_workers=2) as pool:
                tasks=[pool.submit(stale),pool.submit(advance)]
                for task in tasks: task.result(timeout=10)
            self.assertEqual(advanced,first.get('journey','j','owner')['progress'])
            self.assertEqual('preserved',first.get('journey','j','owner')['metadata'])

    def test_progress_write_requires_the_owning_record(self):
        with tempfile.TemporaryDirectory() as folder:
            repository=Repository(Path(folder)/'test.db')
            repository.save('journey','j','owner',{})
            with self.assertRaises(LookupError): repository.update_progress('j','other',{}, {})

    def test_older_refresh_or_previous_progress_cannot_replace_current_tracking(self):
        with tempfile.TemporaryDirectory() as folder:
            repository=Repository(Path(folder)/'test.db')
            progress={'completed_leg_ids':[],'onboard_leg_id':'a'}
            repository.save('journey','j','owner',{'progress':progress})
            newest={'checked_at':'2026-10-11T10:02:00+00:00','progress_snapshot':progress,'message':'new'}
            self.assertTrue(repository.save_tracking('j','owner',newest))
            self.assertFalse(repository.save_tracking('j','owner',dict(newest,checked_at='2026-10-11T10:01:00+00:00',message='old')))
            self.assertFalse(repository.save_tracking('j','owner',dict(newest,checked_at='2026-10-11T10:03:00+00:00',progress_snapshot={'completed_leg_ids':[],'onboard_leg_id':None})))
            self.assertEqual('new',repository.get('tracking','j','owner')['message'])
