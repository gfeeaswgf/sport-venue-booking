let commonURL = '/api';
let token = sessionStorage.getItem('token');
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;
axios.defaults.paramsSerializer = sportUtils.serialize;
axios.interceptors.request.use(function (config) {
  token = sessionStorage.getItem('token');
  if (token) config.headers.authorization = token;
  return config;
});
axios.interceptors.response.use(function (response) {
  if (!response.data.success) return Promise.reject(response.data.errorMsg || '操作失败，请重试');
  return response.data;
}, function (error) {
  var status = error.response && error.response.status;
  if (status === 401) {
    bookingRequest.clear();
    sessionStorage.removeItem('token');
    if (document.body.dataset.page !== 'login') {
      location.href = '/login.html?returnTo=' + encodeURIComponent(sportUtils.safeReturn(location.pathname + location.search));
    }
    return Promise.reject('登录已失效，请重新登录');
  }
  if (status === 403) return Promise.reject('当前账号没有管理权限');
  if (!error.response) return Promise.reject('网络连接失败，请确认后端服务已启动');
  return Promise.reject('服务暂不可用，请稍后重试');
});
const util = {commonURL: commonURL, getUrlParam: function (name) { return new URLSearchParams(location.search).get(name) || ''; }};
