import unittest
from datetime import datetime, timezone, timedelta
from sahara.domain import Mode, SearchRequest
from sahara.places import TERMINALS, resolve, is_kerala
from sahara.interpret import interpret


class PanIndiaCoverageTests(unittest.TestCase):
    def test_pan_india_cities_resolve_to_rail_terminals(self):
        cases = {
            'Delhi': 'NDLS',
            'New Delhi': 'NDLS',
            'Mumbai': 'CSMT',
            'Bombay': 'CSMT',
            'Chennai': 'MAS',
            'Madras': 'MAS',
            'Kolkata': 'HWH',
            'Calcutta': 'HWH',
            'Bengaluru': 'SBC',
            'Bangalore': 'SBC',
            'Hyderabad': 'SC',
            'Secunderabad': 'SC',
            'Ahmedabad': 'ADI',
            'Surat': 'ST',
            'Vadodara': 'BRC',
            'Pune': 'PUNE',
            'Jaipur': 'JP',
            'Lucknow': 'LKO',
            'Kanpur': 'CNB',
            'Varanasi': 'BSB',
            'Prayagraj': 'PRYJ',
            'Patna': 'PNBE',
            'Bhopal': 'BPL',
            'Indore': 'INDB',
            'Nagpur': 'NGP',
            'Chandigarh': 'CDG',
            'Amritsar': 'ASR',
            'Goa': 'MAO',
            'Madgaon': 'MAO',
            'Bhubaneswar': 'BBS',
            'Puri': 'PURI',
            'Guwahati': 'GHY',
            'Jammu': 'JAT',
            'Dehradun': 'DDN',
            'Haridwar': 'HW',
            'Ranchi': 'RNC',
            'Tatanagar': 'TATA',
            'Vijayawada': 'BZA',
            'Visakhapatnam': 'VSKP',
            'Tirupati': 'TPTY',
            'Madurai': 'MDU',
            'Trichy': 'TPJ',
            'Salem': 'SA',
            'Mysuru': 'MYS',
            'Mangaluru': 'MAQ',
            'Hubballi': 'UBL',
            'Thrissur': 'TCR',
            'Palakkad': 'PGT',
            'Shoranur': 'SRR',
            'Kannur': 'CAN',
            'Kollam': 'QLN',
            'Alappuzha': 'ALLP',
            'Kasaragod': 'KGQ',
            'Tirur': 'TIR',
            'Kochi': 'ERS',
            'Kottayam': 'KTYM',
            'Kozhikode': 'CLT',
            'Coimbatore': 'CBE',
        }
        for city, expected_code in cases.items():
            with self.subTest(city=city):
                terminal = resolve(city)
                self.assertEqual(expected_code, terminal.codes.get('rail'))
                self.assertEqual('rail', terminal.kind)

    def test_state_mapping_and_is_kerala_helper(self):
        self.assertTrue(is_kerala('ers'))
        self.assertTrue(is_kerala('kty'))
        self.assertTrue(is_kerala('clt'))
        self.assertTrue(is_kerala('tcr'))
        self.assertFalse(is_kerala('ndls'))
        self.assertFalse(is_kerala('csmt'))
        self.assertFalse(is_kerala('mas'))
        self.assertFalse(is_kerala('sbc'))
        self.assertFalse(is_kerala('adi'))

    def test_outside_kerala_routes_enforce_train_only(self):
        # Origin outside Kerala, Destination inside Kerala
        req1 = interpret('Delhi to Kochi tomorrow')
        self.assertNotIn(Mode.BUS, req1.modes)
        self.assertIn(Mode.TRAIN, req1.modes)

        # Origin inside Kerala, Destination outside Kerala
        req2 = interpret('Kochi to Bengaluru tomorrow')
        self.assertNotIn(Mode.BUS, req2.modes)
        self.assertIn(Mode.TRAIN, req2.modes)

        # Both outside Kerala
        req3 = interpret('Mumbai to Delhi tomorrow')
        self.assertNotIn(Mode.BUS, req3.modes)
        self.assertIn(Mode.TRAIN, req3.modes)

    def test_intra_kerala_routes_retain_buses(self):
        # Both inside Kerala
        req = interpret('Kottayam to Ernakulam tomorrow')
        self.assertIn(Mode.BUS, req.modes)
        self.assertIn(Mode.TRAIN, req.modes)


if __name__ == '__main__':
    unittest.main()
