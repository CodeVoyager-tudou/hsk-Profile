import request from '@/utils/request'

export function listMedicine(query) {
  return request({
    url: '/api/shop/medicine/page',
    method: 'get',
    params: query
  })
}

export function getMedicine(id) {
  return request({
    url: '/api/shop/medicine/' + id,
    method: 'get'
  })
}

export function listCategory() {
  return request({
    url: '/api/shop/medicine/categories',
    method: 'get'
  })
}
