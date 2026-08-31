-- F7.1 — role SUPERADMIN.
--
-- Osa kolmá k členství v organizaci ([ADR 0018]). Není to čtvrtá hodnota v `org_member.role`
-- schválně: tamní role jsou uspořádané a `atLeast()` by cokoli nad OWNER pustilo všemi
-- dnešními kontrolami, tedy i k credentials všech klientů.
--
-- Uděluje se výhradně ze seed CLI. Povýšení přes HTTP je jediná operace, po které by
-- z jednoho kompromitovaného účtu byly dva.

ALTER TABLE app_user
    ADD COLUMN platform_role text CHECK (platform_role IN ('SUPERADMIN'));

COMMENT ON COLUMN app_user.platform_role IS
    'Správa platformy; NULL = běžný uživatel. Nedává přístup k datům organizací.';

-- Takových účtů jsou jednotky — částečný index se do paměti vejde celý.
CREATE INDEX app_user_platform_role_idx ON app_user (platform_role)
    WHERE platform_role IS NOT NULL;
