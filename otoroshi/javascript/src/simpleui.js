import 'es6-shim';
import 'whatwg-fetch';
import 'core-js/es6/map';
import 'core-js/es6/set';
import './raf';
import './style/simpleui.scss';

import Symbol from 'es-symbol';
import $ from 'jquery';
import React from 'react';
import ReactDOM from 'react-dom';
import { SimplifiedApp } from './apps/SimplifiedApp';

import { registerAlert, registerConfirm, registerPrompt, registerPopup } from './components/window';

if (!window.Symbol) {
  window.Symbol = Symbol;
}
window.$ = $;
window.jQuery = $;

window._fetch = window.fetch;
window.fetch = function (...params) {
  const url = params[0];
  const opts = params[1] || {};
  return window._fetch(params[0], { ...opts, credentials: 'include' }).then((r) => {
    if (r.status === 401 || r.status === 403) {
      if (url.indexOf('/bo/simple/login') === -1) {
        window.location.href = '/bo/simple/login';
      }
    }
    return r;
  });
};

function setupWindowUtils() {
  registerAlert();
  registerConfirm();
  registerPrompt();
  registerPopup();
}

export function initSimpleUI(node, env) {
  setupWindowUtils();
  window.__otoroshi__env__latest = env || {};
  ReactDOM.render(<SimplifiedApp env={env} />, node);
}
