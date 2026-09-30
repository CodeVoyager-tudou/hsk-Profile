import request from '@/utils/request'

export function getRouter() {
  return request({
    url: '/api/menu/list',
    method: 'get'
  })
}
