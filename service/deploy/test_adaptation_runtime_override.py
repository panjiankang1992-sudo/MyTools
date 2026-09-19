"""Guard production Reader runtime isolation against inherited legacy credentials."""
import unittest
from deploy_adaptation_production import unit


class RuntimeOverrideTest(unittest.TestCase):
    def test_reader_removes_only_legacy_runtime_overrides(self):
        config = unit('reader', 'reader-service')
        self.assertIn('UnsetEnvironment=READER_RUNTIME_BASE_URL READER_RUNTIME_SECURE_KEY\n', config)
        self.assertIn('Requires=mytools-adaptation-runtime.service\n', config)
        self.assertIn('--spring.config.additional-location=', config)

    def test_other_roles_keep_their_environment(self):
        for role, service in (('scheduler', 'task-scheduler-service'), ('gateway', 'mytools-gateway')):
            self.assertNotIn('UnsetEnvironment=', unit(role, service))


if __name__ == '__main__':
    unittest.main()
