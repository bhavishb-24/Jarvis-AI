"""Bounded, read-only host metrics shared by both companion adapters."""

import shutil
from pathlib import Path


def collect_system_status(platform_name, disk_root=None, psutil_module=None):
    data = {
        "platform": platform_name,
        "cpu_percent": None,
        "memory_percent": None,
        "battery_percent": None,
        "charging": None,
        "disk_free_bytes": None,
        "disk_total_bytes": None,
        "network_connected": None,
        "network_interfaces": [],
    }
    try:
        disk = shutil.disk_usage(disk_root or Path.home())
        data.update(disk_free_bytes=disk.free, disk_total_bytes=disk.total)
    except OSError:
        pass
    if psutil_module is None:
        try:
            import psutil as psutil_module
        except ImportError:
            return data
    try:
        data["cpu_percent"] = psutil_module.cpu_percent(interval=None)
        data["memory_percent"] = psutil_module.virtual_memory().percent
    except (OSError, AttributeError):
        pass
    try:
        battery = psutil_module.sensors_battery()
        if battery is not None:
            data["battery_percent"] = battery.percent
            data["charging"] = battery.power_plugged
    except (OSError, AttributeError):
        pass
    try:
        interfaces = psutil_module.net_if_stats()
        active = sorted(name for name, state in interfaces.items() if state.isup)
        data["network_interfaces"] = active[:32]
        data["network_connected"] = bool(active)
    except (OSError, AttributeError):
        pass
    return data
