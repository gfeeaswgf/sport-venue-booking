Vue.component('sport-header', {
  props: ['profile', 'active'],
  template: `<header class="site-header"><div class="header-inner"><a class="brand" href="/index.html"><span class="brand-mark"><i class="el-icon-position"></i></span><span>跃动校园<small>CAMPUS SPORTS</small></span></a><nav class="desktop-nav"><a href="/index.html" :class="{on:active==='home'}">预约大厅</a><a href="/reservations.html" :class="{on:active==='orders'}">我的预约</a><a v-if="profile && profile.isAdmin" href="/admin.html" :class="{on:active==='admin'}">场馆管理</a></nav><a class="account-link" :href="profile ? '/info.html' : '/login.html'"><span class="avatar"><i class="el-icon-user"></i></span><span>{{profile ? (profile.realName || profile.nickName) : '登录 / 注册'}}</span><i class="el-icon-arrow-right"></i></a></div></header>`
});
Vue.component('sport-nav', {
  props: ['active'],
  template: `<nav class="mobile-nav"><a href="/index.html" :class="{on:active==='home'}"><i class="el-icon-house"></i><span>预约大厅</span></a><a href="/reservations.html" :class="{on:active==='orders'}"><i class="el-icon-tickets"></i><span>我的预约</span></a><a href="/info.html" :class="{on:active==='profile'}"><i class="el-icon-user"></i><span>我的</span></a></nav>`
});
Vue.component('venue-card', {
  props: ['venue'],
  computed: { kind: function () { return sportUtils.category(this.venue.category); } },
  template: `<a class="venue-card" :href="'/shop-detail.html?id='+venue.id"><div class="venue-art" :class="kind.color"><span class="art-category">{{kind.name}} / CAMPUS</span><div class="mini-court"></div><span class="sport-emoji">{{kind.icon}}</span><span class="art-tag">免费预约</span></div><div class="venue-card-body"><div class="venue-card-title"><h3>{{venue.name}}</h3><i class="el-icon-top-right"></i></div><p class="venue-address"><i class="el-icon-location-outline"></i>{{venue.address}}</p><div class="venue-meta"><span><i class="el-icon-time"></i>{{venue.openHours}}</span><span class="open-dot">开放预约</span></div><div class="venue-card-cta"><span>选择场地与时段</span><i class="el-icon-arrow-right"></i></div></div></a>`
});
