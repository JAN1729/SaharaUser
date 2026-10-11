import unittest


class KozhikodeCoverageTests(unittest.TestCase):
    def test_city_aliases_resolve_to_verified_rail_code(self):
        from sahara.places import resolve
        for alias in ['Kozhikode','Calicut','CLT','കോഴിക്കോട്']:
            with self.subTest(alias=alias):
                self.assertEqual('CLT',resolve(alias).codes['rail'])

    def test_discovery_includes_direct_rail_bus_and_airport_pairs(self):
        from sahara.places import discovery_pairs
        pairs={(a.id,b.id) for a,b in discovery_pairs('clt','sbc')}
        self.assertTrue({('clt','sbc'),('kozhikode-bus','bengaluru-bus'),('ccj','blr')}<=pairs)

    def test_bus_mapping_and_local_links_do_not_claim_live_ground_data(self):
        from sahara.places import TERMINALS,transfer_catalog
        self.assertEqual('10072|Kozhikode (11)',TERMINALS['kozhikode-bus'].codes['ksrtc'])
        links={(t.origin,t.destination):t for t in transfer_catalog()}
        for destination in ['kozhikode-bus','ccj']:
            self.assertEqual('estimated',links['clt',destination].evidence)
            self.assertIsNone(links['clt',destination].fare_minor)
