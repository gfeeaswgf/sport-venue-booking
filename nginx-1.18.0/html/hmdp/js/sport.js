(function () {
    'use strict';
    const u = sportUtils;
    const page = document.body.dataset.page;
    new Vue({
        el: '#app',
        data: function () { return {
            page: page, u: u, profile: null, loading: false, busy: false, error: '', keyword: '', category: '', categories: u.categories,
            venues: [], venue: null, courts: [], slots: [], days: u.dates(), selectedDate: u.today(), selectedCourt: null,
            selectedSlot: null, bookingVisible: false, agreement: false, companions: [], profileReady: false,
            orders: [], orderStatus: '', current: 1, total: 0, detail: null, detailVisible: false,
            requestItems: [], requestError: '', requestTimers: {}, requestStartedAt: {},
            requestInFlight: {}, requestEpoch: 0, requestUserId: null,
            profileForm: {realName: '', studentNo: ''}, loginForm: {phone: '', code: ''}, loginAgreed: false, countdown: 0, timer: null,
            adminTab: 'venues', adminVenueId: null, adminCourtId: null, venueDialog: false, courtDialog: false,
            venueForm: {}, courtForm: {}, slotForm: {startTime: '08:00:00', endTime: '09:00:00'},
            isAdmin: false
        }; },
        computed: {
            ready: function () { return this.profileReady && this.profile && this.profile.realName && this.profile.studentNo; },
            active: function () { return page === 'orders' ? 'orders' : page === 'profile' || page === 'edit' ? 'profile' : page === 'admin' ? 'admin' : 'home'; },
            selectedKind: function () { return u.category(this.venue && this.venue.category); },
            profileReturn: function () { return '/info-edit.html?returnTo=' + encodeURIComponent(u.safeReturn(location.pathname + location.search)); }
        },
        created: async function () {
            this.loading = true;
            try {
                if (page === 'login') return;
                if (sessionStorage.getItem('token')) {
                    try { await this.loadProfile(); } catch (e) { if (['orders','profile','edit','admin'].includes(page)) throw e; }
                } else if (['orders','profile','edit','admin'].includes(page)) { this.goLogin(); return; }
                if (page === 'home' || page === 'list') {
                    this.category = util.getUrlParam('category'); await this.loadVenues();
                } else if (page === 'venue') {
                    let id = util.getUrlParam('id');
                    if (!/^\d+$/.test(id)) throw '请选择有效的场馆';
                    let results = await Promise.all([axios.get('/venue/' + id), axios.get('/venue/' + id + '/courts')]);
                    this.venue = results[0].data; this.courts = results[1].data || [];
                    if (this.courts.length) await this.selectCourt(this.courts[0]);
                    await this.restoreBooking();
                } else if (page === 'orders') {
                    const requests = this.restoreRequests();
                    await Promise.all([this.loadOrders(), requests]);
                } else if (page === 'admin') {
                    if (!this.profile || !this.profile.isAdmin) throw '当前账号没有管理权限，请联系平台管理员';
                    this.isAdmin = true; await this.loadAdminVenues();
                }
            } catch (e) { this.error = String(e); }
            finally { this.loading = false; }
        },
        beforeDestroy: function () {
            if (this.timer) {
                clearInterval(this.timer);
            }
            this.stopRequestPolling();
        },
        methods: {
            showError: function (e) { this.$message.error(typeof e === 'string' ? e : '操作未完成，请稍后重试'); },
            goLogin: function () { location.href = '/login.html?returnTo=' + encodeURIComponent(u.safeReturn(location.pathname + location.search)); },
            loadProfile: async function () {
                this.profileReady = false;
                const response = await axios.get('/user/profile');
                if (this.profile && String(this.profile.id) !== String(response.data.id)) {
                    this.stopRequestPolling();
                    this.requestItems = [];
                    this.detail = null;
                    this.detailVisible = false;
                }
                this.profile = response.data;
                bookingRequest.known(this.profile.id);
                bookingRequest.restore(this.profile.id);
                this.profileForm = {realName: this.profile.realName || '', studentNo: this.profile.studentNo || ''};
                this.profileReady = true;
            },
            loadVenues: async function () {
                this.error = ''; this.loading = true;
                try { this.venues = (await axios.get('/venue/list', {params: {name: this.keyword, category: this.category}})).data || []; }
                catch (e) { this.error = String(e); } finally { this.loading = false; }
            },
            chooseCategory: function (code) { this.category = code; this.loadVenues(); },
            selectCourt: async function (court) { this.selectedCourt = court; this.selectedSlot = null; await this.loadSlots(); },
            chooseDate: async function (date) { this.selectedDate = date; this.selectedSlot = null; await this.loadSlots(); },
            loadSlots: async function () {
                if (!this.selectedCourt) return;
                const courtId = this.selectedCourt.id, date = this.selectedDate; this.busy = true; this.slots = [];
                try {
                    let response = await axios.get('/venue/courts/' + courtId + '/slots', {params: {date: date}});
                    if (this.selectedCourt.id === courtId && this.selectedDate === date) this.slots = response.data || [];
                } catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            openBooking: async function (slot) {
                if (!slot.available || this.busy) return;
                if (!sessionStorage.getItem('token')) { this.goLogin(); return; }
                this.busy = true;
                try {
                    await this.loadProfile(); this.selectedSlot = slot; this.companions = []; this.agreement = false; this.bookingVisible = true;
                } catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            restoreBooking: async function () {
                if (!this.profile) {
                    return;
                }
                const pending = bookingRequest.restore(this.profile.id);
                if (!pending || !pending.selection
                        || String(pending.selection.venueId) !== String(this.venue.id)) {
                    return;
                }
                const court = this.courts.find(function (item) {
                    return String(item.id) === String(pending.selection.courtId);
                });
                if (!court || !this.days.some(function (day) {
                    return day.value === pending.selection.date;
                })) {
                    return;
                }
                this.selectedDate = pending.selection.date;
                await this.selectCourt(court);
                const slot = this.slots.find(function (item) {
                    return String(item.id) === String(pending.body.slotId);
                });
                if (slot) {
                    // 即使场次因首次提交成功而显示满员，也允许用原标识查询成功结果。
                    this.selectedSlot = slot;
                    this.companions = pending.body.companions;
                    this.agreement = pending.body.agreement;
                    this.bookingVisible = true;
                }
            },
            addCompanion: function () { if (this.companions.length < this.selectedCourt.maxPeople - 1) this.companions.push({name: '', studentNo: ''}); },
            submitBooking: async function () {
                if (this.busy) {
                    return;
                }
                if (!this.ready) {
                    this.showError('请先完善本人预约资料');
                    return;
                }
                if (!this.agreement) {
                    this.showError('请先阅读并同意预约须知');
                    return;
                }
                this.busy = true;
                const epoch = this.requestEpoch;
                const userId = this.profile.id;
                try {
                    const pending = bookingRequest.prepare(userId, {
                        slotId: this.selectedSlot.id,
                        agreement: this.agreement,
                        companions: this.companions
                    }, {
                        venueId: this.venue.id,
                        courtId: this.selectedCourt.id,
                        date: this.selectedDate
                    });
                    const response = await axios.post('/booking/orders', pending.body, {
                        headers: {'Idempotency-Key': pending.key}
                    });
                    if (!this.isRequestOwner(epoch, userId)) {
                        return;
                    }
                    const result = response.data;
                    if (!this.validRequestResult(result)) {
                        throw '尚未确认受理结果，请使用原预约草稿重试';
                    }
                    bookingRequest.accept(userId, result);
                    if (result.state === 'FAILED') {
                        throw result.message || '本次预约未成功，请重新选择场次';
                    }
                    location.href = '/reservations.html?requestId=' + encodeURIComponent(result.requestId);
                } catch (e) {
                    if (this.isRequestOwner(epoch, userId)) {
                        this.showError(e);
                        // 保留对话框和草稿，网络超时后可直接重试，也可在“我的预约”查看结果。
                        await this.loadSlots();
                    }
                }
                finally {
                    this.busy = false;
                }
            },
            retryBooking: function () {
                // 确定失败后发起新尝试，不沿用网络超时留下的旧标识。
                bookingRequest.clearDraft();
                location.href = '/index.html';
            },
            validRequestResult: function (result) {
                return result && typeof result.requestId === 'string' && result.requestId.length > 0
                    && ['PROCESSING', 'SUCCEEDED', 'FAILED'].includes(result.state)
                    && (result.state !== 'SUCCEEDED' || !!result.orderId);
            },
            stopRequestPolling: function () {
                Object.values(this.requestTimers).forEach(function (timer) {
                    clearTimeout(timer);
                });
                this.requestTimers = {};
                this.requestStartedAt = {};
                this.requestInFlight = {};
                this.requestEpoch++;
            },
            isRequestOwner: function (epoch, userId) {
                return this.requestEpoch === epoch && this.profile
                    && String(this.profile.id) === String(userId);
            },
            restoreRequests: async function () {
                this.stopRequestPolling();
                this.requestItems = [];
                this.requestError = '';
                if (!this.profile) {
                    return;
                }
                const userId = this.profile.id;
                const epoch = this.requestEpoch;
                if (this.requestUserId !== null && String(this.requestUserId) !== String(userId)) {
                    this.orders = [];
                    this.total = 0;
                    this.detail = null;
                    this.detailVisible = false;
                }
                this.requestUserId = userId;
                const known = bookingRequest.known(userId);
                const ids = new Set(known.filter(function (item) {
                    return item.state === 'PROCESSING';
                }).map(function (item) {
                    return item.requestId;
                }));
                const pending = bookingRequest.restore(userId);
                if (pending && pending.requestId) {
                    ids.add(pending.requestId);
                }
                const requestedId = util.getUrlParam('requestId');
                if (requestedId) {
                    ids.add(requestedId);
                }
                for (const result of known) {
                    if (!this.isRequestOwner(epoch, userId)) {
                        return;
                    }
                    if (result.state === 'PROCESSING' && this.validRequestResult(result)) {
                        // 已受理的本地记录先展示，列表请求较慢时仍能看到处理中。
                        await this.applyRequestResult(result);
                    }
                }
                if (!this.isRequestOwner(epoch, userId)) {
                    return;
                }
                try {
                    const response = await axios.get('/booking/requests');
                    if (!this.isRequestOwner(epoch, userId)) {
                        return;
                    }
                    for (const result of response.data || []) {
                        if (!this.isRequestOwner(epoch, userId)) {
                            return;
                        }
                        if (this.validRequestResult(result)) {
                            ids.delete(result.requestId);
                            await this.applyRequestResult(result);
                        }
                    }
                } catch (failure) {
                    if (this.isRequestOwner(epoch, userId)) {
                        this.requestError = '暂时无法恢复预约请求，请继续查询';
                    }
                }
                if (!this.isRequestOwner(epoch, userId)) {
                    return;
                }
                for (const id of ids) {
                    // 最近列表不含已成功请求，仍需查询本地已知的受理标识。
                    await this.queryRequest(id, true);
                }
            },
            scheduleRequest: function (item) {
                const id = item.requestId;
                clearTimeout(this.requestTimers[id]);
                delete this.requestTimers[id];
                if (item.state !== 'PROCESSING') {
                    return;
                }
                if (Date.now() - this.requestStartedAt[id] >= 60000) {
                    item.paused = true;
                    return;
                }
                item.paused = false;
                this.requestTimers[id] = setTimeout(async () => {
                    delete this.requestTimers[id];
                    if (Date.now() - this.requestStartedAt[id] >= 60000) {
                        item.paused = true;
                        return;
                    }
                    await this.queryRequest(id, false);
                }, 500);
            },
            applyRequestResult: async function (result) {
                if (!this.validRequestResult(result)) {
                    throw '查询结果暂不可用，请继续查询';
                }
                const userId = this.profile.id;
                const epoch = this.requestEpoch;
                const saved = bookingRequest.record(userId, result);
                let item = this.requestItems.find(function (entry) {
                    return entry.requestId === result.requestId;
                });
                if (!item) {
                    item = {
                        requestId: result.requestId,
                        state: result.state,
                        orderId: result.orderId,
                        message: result.message || '',
                        paused: false,
                        querying: false,
                        queryError: ''
                    };
                    this.requestItems.push(item);
                }
                item.state = result.state;
                item.orderId = result.orderId;
                item.message = result.message || '';
                item.queryError = '';
                if (this.requestStartedAt[item.requestId] === undefined) {
                    this.requestStartedAt[item.requestId] = Date.now();
                }
                this.scheduleRequest(item);
                if (result.state === 'SUCCEEDED') {
                    this.requestItems = this.requestItems.filter(function (entry) {
                        return entry.requestId !== result.requestId;
                    });
                    if (!saved.notified) {
                        bookingRequest.record(userId, result, true);
                        this.$message.success('预约成功');
                    }
                    await this.openDetail({id: result.orderId});
                    if (this.isRequestOwner(epoch, userId)) {
                        await this.loadOrders();
                    }
                }
            },
            queryRequest: async function (id, restart) {
                if (!this.profile || this.requestInFlight[id]) {
                    return;
                }
                const epoch = this.requestEpoch;
                const userId = this.profile.id;
                if (String(this.requestUserId) !== String(userId)) {
                    return;
                }
                let item = this.requestItems.find(function (entry) {
                    return entry.requestId === id;
                });
                if (restart || this.requestStartedAt[id] === undefined) {
                    this.requestStartedAt[id] = Date.now();
                }
                this.requestInFlight[id] = true;
                if (item) {
                    item.querying = true;
                }
                try {
                    const response = await axios.get('/booking/requests/' + encodeURIComponent(id));
                    if (this.isRequestOwner(epoch, userId)) {
                        await this.applyRequestResult(response.data);
                    }
                } catch (failure) {
                    if (this.isRequestOwner(epoch, userId)) {
                        // 网络异常和查询超时不等于确定失败，保留受理状态。
                        if (!item) {
                            item = {
                                requestId: id,
                                state: 'PROCESSING',
                                orderId: null,
                                message: '',
                                paused: false,
                                querying: false,
                                queryError: ''
                            };
                            this.requestItems.push(item);
                        }
                        item.queryError = '暂未确认结果，请稍后继续查询';
                        this.scheduleRequest(item);
                    }
                } finally {
                    if (this.isRequestOwner(epoch, userId)) {
                        delete this.requestInFlight[id];
                        if (item) {
                            item.querying = false;
                        }
                    }
                }
            },
            loadOrders: async function () {
                this.loading = true;
                this.error = '';
                const epoch = this.requestEpoch;
                const userId = this.profile && this.profile.id;
                try {
                    const response = await axios.get('/booking/orders', {params: {status: this.orderStatus, current: this.current}});
                    if (!this.isRequestOwner(epoch, userId)) {
                        return;
                    }
                    this.orders = response.data || [];
                    this.total = response.total || 0;
                    const id = util.getUrlParam('id');
                    if (id && !this.detail) {
                        await this.openDetail({id: id});
                    }
                } catch (e) {
                    if (this.isRequestOwner(epoch, userId)) {
                        this.error = String(e);
                    }
                } finally {
                    this.loading = false;
                }
            },
            filterOrders: function (status) { this.orderStatus = status; this.current = 1; this.loadOrders(); },
            openDetail: async function (order) {
                const epoch = this.requestEpoch;
                const userId = this.profile && this.profile.id;
                try {
                    const response = await axios.get('/booking/orders/' + encodeURIComponent(order.id));
                    if (this.isRequestOwner(epoch, userId)) {
                        this.detail = response.data;
                        this.detailVisible = true;
                    }
                } catch (e) {
                    if (this.isRequestOwner(epoch, userId)) {
                        this.showError(e);
                    }
                }
            },
            cancelOrder: async function (order) {
                try { await this.$confirm('取消后将释放名额，同行人凭证同时失效。确认取消？', '取消预约', {type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '保留预约'}); }
                catch (_) { return; }
                this.busy = true;
                try { await axios.put('/booking/orders/' + encodeURIComponent(order.id) + '/cancel'); this.detailVisible = false; this.$message.success('预约已取消，名额已释放'); await this.loadOrders(); }
                catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            saveProfile: async function () {
                this.busy = true;
                try {
                    await axios.put('/user/profile', this.profileForm); this.$message.success('预约资料已保存');
                    location.href = u.safeReturn(util.getUrlParam('returnTo') || '/info.html');
                } catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            logout: async function () {
                try {
                    await axios.post('/user/logout');
                    this.stopRequestPolling();
                    bookingRequest.clear();
                    sessionStorage.removeItem('token');
                    sessionStorage.removeItem('userInfo');
                    location.href = '/index.html';
                }
                catch (e) { this.showError(e); }
            },
            sendCode: async function () {
                if (this.countdown || this.busy) return;
                if (!/^1\d{10}$/.test(this.loginForm.phone)) { this.showError('请输入正确的11位手机号'); return; }
                this.busy = true;
                try {
                    await axios.post('/user/code', null, {params: {phone: this.loginForm.phone}});
                    this.$message.success('验证码已生成，请在后端控制台查看'); this.countdown = 60;
                    this.timer = setInterval(() => { this.countdown--; if (this.countdown <= 0) { clearInterval(this.timer); this.timer = null; } }, 1000);
                } catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            login: async function () {
                if (!this.loginAgreed) { this.showError('请阅读并勾选预约使用说明'); return; }
                if (!/^1\d{10}$/.test(this.loginForm.phone) || !/^\d{6}$/.test(this.loginForm.code)) { this.showError('请填写正确的手机号和6位验证码'); return; }
                this.busy = true;
                try {
                    let response = await axios.post('/user/login', this.loginForm);
                    bookingRequest.clear();
                    sessionStorage.setItem('token', response.data);
                    location.href = u.safeReturn(util.getUrlParam('returnTo'));
                } catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            loadAdminVenues: async function () {
                this.venues = (await axios.get('/admin/venues')).data || [];
                if (!this.adminVenueId && this.venues.length) this.adminVenueId = this.venues[0].id;
                await this.loadAdminCourts();
            },
            loadAdminCourts: async function () {
                if (!this.adminVenueId) { this.courts = []; return; }
                this.courts = (await axios.get('/admin/courts', {params: {venueId: this.adminVenueId}})).data || [];
                if (!this.courts.some(c => c.id === this.adminCourtId)) this.adminCourtId = this.courts.length ? this.courts[0].id : null;
                await this.loadAdminSlots();
            },
            loadAdminSlots: async function () {
                if (!this.adminCourtId) { this.slots = []; return; }
                this.slots = (await axios.get('/admin/slots', {params: {courtId: this.adminCourtId, date: this.selectedDate}})).data || [];
            },
            adminChanged: async function () {
                try { if (this.adminTab === 'orders') await this.loadAdminOrders(); else await this.loadAdminVenues(); } catch (e) { this.showError(e); }
            },
            adminVenueChanged: async function () { this.adminCourtId = null; try { await this.loadAdminCourts(); } catch (e) { this.showError(e); } },
            adminSlotsChanged: async function () { try { await this.loadAdminSlots(); } catch (e) { this.showError(e); } },
            editVenue: function (v) {
                this.venueForm = v ? Object.assign({}, v) : {name: '', category: 'BADMINTON', address: '', openHours: '08:00—21:00', description: '', notice: '', enabled: true, cancelAdvanceMinutes: 60}; this.venueDialog = true;
            },
            editCourt: function (c) {
                this.courtForm = c ? Object.assign({}, c) : {venueId: this.adminVenueId, name: '', mode: 'COURT', capacity: 1, maxPeople: 6, enabled: true}; this.courtDialog = true;
            },
            saveAdminVenue: async function () { await this.saveAdmin('/admin/venues', this.venueForm, 'venueDialog'); },
            saveAdminCourt: async function () { await this.saveAdmin('/admin/courts', this.courtForm, 'courtDialog'); },
            saveAdmin: async function (url, form, dialog) {
                this.busy = true;
                try { await axios.post(url, form); this[dialog] = false; this.$message.success('已保存'); await this.loadAdminVenues(); }
                catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            publishSlot: async function () {
                this.busy = true;
                try { await axios.post('/admin/slots', {courtId: this.adminCourtId, bookingDate: this.selectedDate, startTime: this.slotForm.startTime, endTime: this.slotForm.endTime}); this.$message.success('时段已发布'); await this.loadAdminSlots(); }
                catch (e) { this.showError(e); } finally { this.busy = false; }
            },
            toggleSlot: async function (slot) {
                try { await axios.put('/admin/slots/' + slot.id + '/enabled', null, {params: {enabled: !slot.enabled}}); await this.loadAdminSlots(); }
                catch (e) { this.showError(e); }
            },
            loadAdminOrders: async function () {
                this.error = '';
                try {
                    let response = await axios.get('/admin/orders', {params: {status: this.orderStatus, current: this.current}});
                    this.orders = response.data || []; this.total = response.total || 0;
                } catch (e) {
                    // 翻页失败不能将上一页记录误标为当前页，避免管理员误判。
                    this.orders = []; this.total = 0; this.error = String(e); this.showError(e);
                }
            },
            adminFilterOrders: async function () { this.current = 1; try { await this.loadAdminOrders(); } catch (e) { this.showError(e); } },
            verifyOrder: async function (order) {
                let input; try { input = await this.$prompt('请核对入场人出示的12位核销码', '入场核销', {inputPattern: /^[A-Za-z0-9]{12}$/, inputErrorMessage: '请输入12位核销码'}); } catch (_) { return; }
                this.busy = true;
                try { await axios.put('/admin/orders/' + encodeURIComponent(order.id) + '/verify', null, {params: {code: input.value}}); this.$message.success('核销成功'); await this.loadAdminOrders(); }
                catch (e) { this.showError(e); } finally { this.busy = false; }
            }
        }
    });
})();
