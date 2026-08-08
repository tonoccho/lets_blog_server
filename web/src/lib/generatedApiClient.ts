// Generated API client from OpenAPI spec
// This module re-exports the generated client with enhanced configuration

import {
  listSites as _listSites,
  getSiteDetail as _getSiteDetail,
  registerSite as _registerSite,
  updateSite as _updateSite,
  deleteSite as _deleteSite,
  listPosts as _listPosts,
  login as _login,
  listUsers as _listUsers,
  type Site,
  type SiteDetail,
  type SiteRegisterInput,
  type SiteUpdateInput,
  type PostSummary,
  type LoginRequest,
  type LoginResult,
  type AuthenticatedUser,
  type AppUser,
} from '@api-client';

// Re-export types
export type {
  Site,
  SiteDetail,
  SiteRegisterInput,
  SiteUpdateInput,
  PostSummary,
  LoginRequest,
  LoginResult,
  AuthenticatedUser,
  AppUser,
};

// Re-export generated client functions
export const generatedApiClient = {
  sites: {
    list: _listSites,
    get: _getSiteDetail,
    register: _registerSite,
    update: _updateSite,
    delete: _deleteSite,
  },
  posts: {
    list: _listPosts,
  },
  auth: {
    login: _login,
  },
  users: {
    list: _listUsers,
  },
};
