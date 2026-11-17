(function (root) {
  'use strict';
  var categories = {
    BADMINTON: {name: '羽毛球', icon: '🏸', color: 'mint'},
    TENNIS: {name: '网球', icon: '🎾', color: 'sand'},
    SWIMMING: {name: '游泳', icon: '🏊', color: 'sky'},
    BILLIARDS: {name: '台球', icon: '🎱', color: 'lilac'}
  };
  var utils = {
    categories: categories,
    category: function (code) { return categories[code] || {name: '运动场馆', icon: '⚑', color: 'sky'}; },
    safeReturn: function (value) {
      if (typeof value !== 'string' || /[\\\r\n]/.test(value)) return '/index.html';
      return /^\/(index|shop-list|shop-detail|reservations|info|info-edit|admin)\.html(?:\?[^#]*)?$/.test(value) ? value : '/index.html';
    },
    serialize: function (params) {
      return Object.keys(params).filter(function (k) { return params[k] !== undefined && params[k] !== null && params[k] !== ''; })
        .map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); }).join('&');
    },
    time: function (value) { return value ? String(value).slice(0, 5) : ''; },
    today: function () {
      var parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date());
      var p = {}; parts.forEach(function (x) { p[x.type] = x.value; }); return p.year + '-' + p.month + '-' + p.day;
    },
    dates: function () {
      var start = utils.today();
      return Array.from({length: 7}, function (_, i) {
        var date = new Date(start + 'T12:00:00+08:00'); date.setUTCDate(date.getUTCDate() + i);
        var value = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).format(date);
        return {value: value, label: i === 0 ? '今天' : i === 1 ? '明天' : new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', weekday: 'short'}).format(date), short: value.slice(5).replace('-', '/')};
      });
    }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = utils;
  else root.sportUtils = utils;
})(typeof window !== 'undefined' ? window : this);
