(function (root) {
    'use strict';

    const storageKey = 'booking.pending';
    const historyKey = 'booking.requests';
    const ttlMillis = 3 * 24 * 60 * 60 * 1000;

    function clearDraft() {
        root.sessionStorage.removeItem(storageKey);
    }

    function clear() {
        clearDraft();
        root.sessionStorage.removeItem(historyKey);
    }

    function normalize(body) {
        return {
            slotId: body.slotId,
            agreement: body.agreement === true,
            companions: body.companions.map(function (person) {
                return {
                    name: person.name.trim(),
                    studentNo: person.studentNo.trim()
                };
            })
        };
    }

    function restore(userId) {
        try {
            const value = JSON.parse(root.sessionStorage.getItem(storageKey));
            if (!value) {
                return null;
            }
            const age = root.Date.now() - value.createdAt;
            if (String(value.userId) !== String(userId) || age < 0 || age >= ttlMillis
                    || !value.body || !value.key || !Array.isArray(value.body.companions)) {
                clear();
                return null;
            }
            return value;
        } catch (failure) {
            clear();
            return null;
        }
    }

    function uuid() {
        if (root.crypto && root.crypto.randomUUID) {
            return root.crypto.randomUUID();
        }
        if (!root.crypto || !root.crypto.getRandomValues) {
            throw '当前浏览器不支持安全请求标识，请使用新版浏览器';
        }
        const bytes = root.crypto.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        const hex = Array.from(bytes, function (value) {
            return value.toString(16).padStart(2, '0');
        }).join('');
        return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16)
                + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
    }

    function prepare(userId, body, selection) {
        known(userId);
        const normalized = normalize(body);
        const previous = restore(userId);
        if (previous && JSON.stringify(previous.body) === JSON.stringify(normalized)) {
            return previous;
        }

        // 保存成功才发送请求；网络失败后继续使用同一份草稿和请求标识。
        const pending = {
            userId: String(userId),
            key: uuid(),
            body: normalized,
            selection: selection || null,
            createdAt: root.Date.now()
        };
        try {
            root.sessionStorage.setItem(storageKey, JSON.stringify(pending));
        } catch (failure) {
            throw '无法保存本次预约草稿，请检查浏览器存储设置后重试';
        }
        return pending;
    }

    function known(userId) {
        try {
            const history = JSON.parse(root.sessionStorage.getItem(historyKey));
            if (!history) {
                return [];
            }
            if (String(history.userId) !== String(userId) || !Array.isArray(history.requests)) {
                clear();
                return [];
            }
            return history.requests.filter(function (item) {
                const age = root.Date.now() - item.updatedAt;
                return item.requestId && age >= 0 && age < ttlMillis;
            });
        } catch (failure) {
            root.sessionStorage.removeItem(historyKey);
            return [];
        }
    }

    function record(userId, result, notified) {
        const history = known(userId);
        const previous = history.find(function (item) {
            return item.requestId === result.requestId;
        });
        const item = {
            requestId: result.requestId,
            state: result.state,
            orderId: result.orderId || null,
            message: result.message || '',
            notified: !!(notified || previous && previous.notified),
            updatedAt: root.Date.now()
        };
        const requests = history.filter(function (entry) {
            return entry.requestId !== result.requestId;
        });
        requests.unshift(item);
        root.sessionStorage.setItem(historyKey, JSON.stringify({
            userId: String(userId),
            requests: requests.slice(0, 20)
        }));
        const pending = restore(userId);
        if (result.state !== 'PROCESSING' && pending && pending.requestId === result.requestId) {
            clearDraft();
        }
        return item;
    }

    function accept(userId, result) {
        const pending = restore(userId);
        if (pending) {
            pending.requestId = result.requestId;
            root.sessionStorage.setItem(storageKey, JSON.stringify(pending));
        }
        return record(userId, result);
    }

    root.bookingRequest = {
        prepare: prepare,
        restore: restore,
        known: known,
        record: record,
        accept: accept,
        clearDraft: clearDraft,
        clear: clear
    };
})(window);
