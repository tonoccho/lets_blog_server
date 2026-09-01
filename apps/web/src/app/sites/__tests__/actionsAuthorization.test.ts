/**
 * @jest-environment node
 */

/**
 * issue #824: Server Action の認可が**実行時に効いている**ことの検証。
 *
 * <p>同Issueの契約テスト(`src/__tests__/serverActionAuthorization.test.ts`)は
 * 「認可呼び出しがソースに書かれているか」を見るだけで、実際に弾かれることは検証していない。
 * ここでは `@/lib/session` の実装を通して、
 * **未ログイン・非admin のときにバックエンド呼び出しへ到達しない**ことを固定する。
 *
 * <p>`requireSession()` / `requireAdminSession()` は `redirect()` を呼ぶ。Next.js の
 * `redirect` は `NEXT_REDIRECT` を投げて制御を打ち切る仕組みなので、
 * ここでも throw するモックにして「その先へ進まない」ことを検証できる形にする。
 */
jest.mock('server-only', () => ({}));

const redirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => redirect(p) }));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const getServerSession = jest.fn();
jest.mock('next-auth', () => ({
  getServerSession: (...args: unknown[]) => getServerSession(...args),
}));
jest.mock('@/lib/auth', () => ({ authOptions: {} }));

const registerSite = jest.fn();
const createManagedWordPressSite = jest.fn();
const checkSiteConnection = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  registerSite: (...a: unknown[]) => registerSite(...a),
  createManagedWordPressSite: (...a: unknown[]) => createManagedWordPressSite(...a),
  checkSiteConnection: (...a: unknown[]) => checkSiteConnection(...a),
  deleteSite: jest.fn(),
  updateSite: jest.fn(),
  installWpCli: jest.fn(),
  generateStaticContent: jest.fn(),
  generateSshKeyPair: jest.fn(),
  listStaticContent: jest.fn(),
}));

import {
  checkSiteConnectionAction,
  createManagedWordPressSiteAction,
  registerSiteAction,
} from '../actions';

/** `registerSiteAction` / `createManagedWordPressSiteAction` が要求する最小限のフォーム。 */
function siteForm(): FormData {
  const form = new FormData();
  form.set('name', 'site');
  form.set('siteKey', 'site-key');
  form.set('cmsType', 'WORDPRESS');
  return form;
}

function managedForm(): FormData {
  const form = new FormData();
  for (const [k, v] of Object.entries({
    managedName: 'n',
    managedSiteKey: 'k',
    managedTitle: 't',
    managedAdminUser: 'u',
    managedAdminEmail: 'e@example.test',
    managedAdminPassword: 'p',
  })) {
    form.set(k, v);
  }
  return form;
}

describe('sites の Server Action の認可(issue #824)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('未ログイン', () => {
    beforeEach(() => getServerSession.mockResolvedValue(null));

    it('registerSiteAction は /login へ送り、サイトを作成しない', async () => {
      await expect(registerSiteAction({}, siteForm())).rejects.toThrow('NEXT_REDIRECT:/login');
      expect(registerSite).not.toHaveBeenCalled();
    });

    it('createManagedWordPressSiteAction は /login へ送り、作成しない', async () => {
      await expect(createManagedWordPressSiteAction({}, managedForm()))
        .rejects.toThrow('NEXT_REDIRECT:/login');
      expect(createManagedWordPressSite).not.toHaveBeenCalled();
    });

    it('checkSiteConnectionAction は /login へ送り、外部への疎通確認をしない', async () => {
      await expect(checkSiteConnectionAction(1)).rejects.toThrow('NEXT_REDIRECT:/login');
      expect(checkSiteConnection).not.toHaveBeenCalled();
    });
  });

  describe('ログイン済みだが非admin', () => {
    beforeEach(() => getServerSession.mockResolvedValue({ user: { role: 'user' } }));

    it('registerSiteAction は / へ送り、サイトを作成しない', async () => {
      await expect(registerSiteAction({}, siteForm())).rejects.toThrow('NEXT_REDIRECT:/');
      expect(registerSite).not.toHaveBeenCalled();
    });

    it('createManagedWordPressSiteAction は / へ送り、作成しない', async () => {
      await expect(createManagedWordPressSiteAction({}, managedForm()))
        .rejects.toThrow('NEXT_REDIRECT:/');
      expect(createManagedWordPressSite).not.toHaveBeenCalled();
    });

    /** 疎通確認は admin 限定にしていない(UIもログイン済み全員に見せている)。 */
    it('checkSiteConnectionAction は非adminでも通る', async () => {
      checkSiteConnection.mockResolvedValue({ status: 'SUCCESS' });

      await expect(checkSiteConnectionAction(1)).resolves.toEqual({ status: 'SUCCESS' });
      expect(checkSiteConnection).toHaveBeenCalledWith(1);
    });
  });

  describe('admin', () => {
    beforeEach(() => getServerSession.mockResolvedValue({ user: { role: 'admin' } }));

    /**
     * 認可で止まらないことだけを見る。この先はフォームのバリデーション
     * (SSH認証情報の必須項目)に進むが、それは本テストの関心ではない。
     */
    it('registerSiteAction は認可で止まらない', async () => {
      await registerSiteAction({}, siteForm());

      expect(redirect).not.toHaveBeenCalled();
    });

    it('createManagedWordPressSiteAction は認可を通り、作成へ進む', async () => {
      createManagedWordPressSite.mockResolvedValue(undefined);

      await createManagedWordPressSiteAction({}, managedForm());

      expect(redirect).not.toHaveBeenCalled();
      expect(createManagedWordPressSite).toHaveBeenCalled();
    });
  });
});
