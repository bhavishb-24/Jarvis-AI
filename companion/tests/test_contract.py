import unittest

from companion.contract import requires_approval, validate_step


class ContractTests(unittest.TestCase):
    def test_unknown_action_is_rejected(self):
        with self.assertRaises(ValueError):
            validate_step({"action": "shell.run", "args": {"command": "whoami"}})

    def test_sensitive_input_requires_approval(self):
        self.assertTrue(requires_approval("input.click"))
        self.assertFalse(requires_approval("system.status"))

    def test_arguments_must_be_object(self):
        with self.assertRaises(ValueError):
            validate_step({"action": "screen.capture", "args": "bad"})

    def test_valid_action_keeps_typed_args(self):
        step = validate_step({"action": "file.search", "args": {"query": "report"}})
        self.assertEqual(step["action"], "file.search")
        self.assertEqual(step["args"]["query"], "report")


if __name__ == "__main__":
    unittest.main()
