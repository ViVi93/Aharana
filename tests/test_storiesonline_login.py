"""Regression tests for StoriesOnline's redirected WLPC login form."""
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'app/src/main/python'))
from fanficfare.configurable import Configuration
from fanficfare.adapters.adapter_storiesonlinenet import StoriesOnlineNetAdapter


class StoriesOnlineLoginTest(unittest.TestCase):
    def setUp(self):
        config = Configuration(['storiesonline.net'], 'epub', lightweight=True)
        self.adapter = StoriesOnlineNetAdapter(
            config, 'https://storiesonline.net/n/64058/lessons-in-k-pop')
        self.adapter.username = 'fixture@example.invalid'
        self.adapter.password = 'fixture-password'
        self.login_url = 'https://login.wlpc.com/?r=fixture%2Freturn%3D'

    def submit_form(self, action):
        html = ('<h1>WLPC log in System</h1><form method="POST"' + action +
                '><input name="token" value="fixture-token"></form>')
        with patch.object(self.adapter, 'get_request_redirected',
                          return_value=(html, self.login_url)), \
             patch.object(self.adapter, 'post_request', return_value='Logged in') as post:
            self.adapter.performLogin(self.adapter.url)
        return post

    def test_missing_action_posts_to_redirected_url_with_query(self):
        post = self.submit_form('')
        post.assert_called_once_with(self.login_url, {
            'token': 'fixture-token', 'email': 'fixture@example.invalid',
            'password': 'fixture-password', 'cmd': 'LOGIN'}, usecache=False)

    def test_explicit_relative_action_is_resolved(self):
        post = self.submit_form(' action="/authenticate"')
        self.assertEqual(post.call_args.args[0], 'https://login.wlpc.com/authenticate')

    def test_empty_action_posts_to_redirected_url_with_query(self):
        post = self.submit_form(' action=""')
        self.assertEqual(post.call_args.args[0], self.login_url)


if __name__ == '__main__':
    unittest.main()
