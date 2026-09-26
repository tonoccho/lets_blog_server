import { NAV_ITEMS, ADMIN_NAV_ITEMS, type NavItem } from '../navigation'
import messagesJa from '../../../messages/ja.json'
import messagesEn from '../../../messages/en.json'

describe('Navigation', () => {
  describe('NAV_ITEMS', () => {
    it('contains regular navigation items', () => {
      expect(NAV_ITEMS.length).toBeGreaterThan(0)
    })

    it('has required properties for each item', () => {
      NAV_ITEMS.forEach((item: NavItem) => {
        expect(item).toHaveProperty('href')
        expect(item).toHaveProperty('labelKey')
        expect(item).toHaveProperty('icon')
        expect(typeof item.href).toBe('string')
        expect(typeof item.labelKey).toBe('string')
        expect(item.icon).toBeDefined()
      })
    })

    it('includes ダッシュボード item', () => {
      const dashboardItem = NAV_ITEMS.find((item) => item.href === '/')
      expect(dashboardItem).toBeDefined()
      expect(dashboardItem?.labelKey).toBe('dashboard')
    })

    it('includes サイト item', () => {
      const sitesItem = NAV_ITEMS.find((item) => item.href === '/sites')
      expect(sitesItem).toBeDefined()
      expect(sitesItem?.labelKey).toBe('sites')
    })

    it('does not include admin-only items', () => {
      const adminItems = NAV_ITEMS.filter((item) => item.adminOnly)
      expect(adminItems.length).toBe(0)
    })
  })

  describe('ADMIN_NAV_ITEMS', () => {
    it('contains admin navigation items', () => {
      expect(ADMIN_NAV_ITEMS.length).toBeGreaterThan(0)
    })

    it('all items have adminOnly flag', () => {
      ADMIN_NAV_ITEMS.forEach((item) => {
        expect(item.adminOnly).toBe(true)
      })
    })

    it('all items are in admin group', () => {
      ADMIN_NAV_ITEMS.forEach((item) => {
        expect(item.group).toBe('admin')
      })
    })

    it('has required properties for each item', () => {
      ADMIN_NAV_ITEMS.forEach((item: NavItem) => {
        expect(item).toHaveProperty('href')
        expect(item).toHaveProperty('labelKey')
        expect(item).toHaveProperty('icon')
        expect(item).toHaveProperty('adminOnly')
        expect(item).toHaveProperty('group')
        expect(typeof item.href).toBe('string')
        expect(typeof item.labelKey).toBe('string')
        expect(item.icon).toBeDefined()
      })
    })
  })

  describe('Navigation consistency', () => {
    it('all items have unique hrefs', () => {
      const allItems = [...NAV_ITEMS, ...ADMIN_NAV_ITEMS]
      const hrefs = allItems.map((item) => item.href)
      const uniqueHrefs = new Set(hrefs)
      expect(uniqueHrefs.size).toBe(hrefs.length)
    })

    it('all hrefs start with /', () => {
      const allItems = [...NAV_ITEMS, ...ADMIN_NAV_ITEMS]
      allItems.forEach((item) => {
        expect(item.href).toMatch(/^\//)
      })
    })
  })

  describe('nav namespace of the message files', () => {
    const itemKeys = [...NAV_ITEMS, ...ADMIN_NAV_ITEMS].map((item) => item.labelKey)

    it.each([
      ['ja', messagesJa],
      ['en', messagesEn],
    ])('has exactly the keys the navigation items use (%s)', (_locale, messages) => {
      expect(Object.keys(messages.nav).sort()).toEqual([...itemKeys].sort())
    })

    it('uses a distinct key for every item', () => {
      expect(new Set(itemKeys).size).toBe(itemKeys.length)
    })
  })
})
