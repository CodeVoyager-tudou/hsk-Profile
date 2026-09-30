import request from '@/utils/request'

export function getPoints(userId) {
  return request({
    url: '/api/points/' + userId,
    method: 'get'
  })
}

export function signIn(userId) {
  return request({
    url: '/api/points/sign-in',
    method: 'post',
    params: { userId }
  })
}

export function getWeekData(userId) {
  return request({
    url: '/api/points/sign-in/week',
    method: 'get',
    params: { userId }
  })
}

export function listRecords(userId) {
  return request({
    url: '/api/points/record/' + userId,
    method: 'get'
  })
}
