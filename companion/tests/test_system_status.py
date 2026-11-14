import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from companion.system_status import collect_system_status


class SystemStatusTests(unittest.TestCase):
    def test_reports_metrics_and_active_network_interfaces(self):
        fake = SimpleNamespace(
            cpu_percent=lambda interval: 12.5,
            virtual_memory=lambda: SimpleNamespace(percent=37.0),
            sensors_battery=lambda: SimpleNamespace(percent=81, power_plugged=True),
            net_if_stats=lambda: {"wifi": SimpleNamespace(isup=True),
                                  "ethernet": SimpleNamespace(isup=False)},
        )
        with tempfile.TemporaryDirectory() as directory:
            result = collect_system_status("test", Path(directory), fake)
        self.assertEqual(result["battery_percent"], 81)
        self.assertEqual(result["cpu_percent"], 12.5)
        self.assertGreater(result["disk_total_bytes"], 0)
        self.assertEqual(result["network_interfaces"], ["wifi"])
        self.assertTrue(result["network_connected"])

    def test_missing_optional_metrics_remain_explicit(self):
        fake = SimpleNamespace()
        result = collect_system_status("test", psutil_module=fake)
        self.assertIsNone(result["battery_percent"])
        self.assertIsNone(result["network_connected"])
