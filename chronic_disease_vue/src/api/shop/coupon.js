import request from '@/utils/request'

export function listActivity() {
  return request({
    url: '/api/shop/coupon/activity',
    method: 'get'
  })
}

export function receiveCoupon(query) {
  return request({
    url: '/api/shop/coupon/receive',
    method: 'post',
    params: query
  })
}

export function listMyCoupons(userId, status) {
  return request({
    url: '/api/shop/coupon/my/' + userId,
    method: 'get',
    params: { status }
  })
}
