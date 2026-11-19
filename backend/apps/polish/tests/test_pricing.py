from django.test import SimpleTestCase, override_settings

from apps.polish.pricing import cost_micros, estimate_tokens


class PricingTest(SimpleTestCase):
    def test_estimate_tokens(self):
        self.assertEqual(estimate_tokens(""), 1)
        self.assertGreater(estimate_tokens("一" * 1600), 900)

    @override_settings(
        POLISH_PRICES={"flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0}}
    )
    def test_cost_idle(self):
        usage = {
            "prompt_cache_hit_tokens": 1_000_000,
            "prompt_cache_miss_tokens": 1_000_000,
            "completion_tokens": 1_000_000,
        }
        self.assertEqual(cost_micros(usage, idle=True, model="flash"), 5_020_000)

    @override_settings(
        POLISH_PRICES={"flash": {"input_hit": 0.02, "input_miss": 1.0, "output": 4.0}}
    )
    def test_cost_peak_doubles(self):
        usage = {
            "prompt_cache_hit_tokens": 0,
            "prompt_cache_miss_tokens": 1_000_000,
            "completion_tokens": 0,
        }
        self.assertEqual(cost_micros(usage, idle=False, model="flash"), 2_000_000)
