import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
    vus: 100,          // 동시 사용자 수
    duration: "2s",    // 2초 동안 동시에 쏘기
};

export default function () {
    const url = "http://localhost:8081/orders";
    const payload = JSON.stringify([
        { userId: "11111111-1111-1111-1111-111111111111", totalAmount: 1000 }
    ]);

    const params = { headers: { "Content-Type": "application/json" } };

    const res = http.post(url, payload, params);

    check(res, { "status is 200": (r) => r.status === 200 });
    sleep(0.01);
}
