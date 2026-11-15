-- SPDX-FileCopyrightText: The LineageOS Project
-- SPDX-License-Identifier: Apache-2.0

-- Twelve account sync: one row per account holding its favorites, playlists and settings.
-- Run it once in the Supabase dashboard: SQL Editor, New query, paste, Run.

create table if not exists public.twelve_library (
    user_id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
    data jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    -- A library is a few hundred kilobytes at most, this keeps the table from being misused
    constraint twelve_library_size check (pg_column_size(data) < 2000000)
);

-- Every account sees and changes its own row only
alter table public.twelve_library enable row level security;

drop policy if exists "Read own library" on public.twelve_library;
create policy "Read own library" on public.twelve_library
    for select to authenticated
    using ((select auth.uid()) = user_id);

drop policy if exists "Add own library" on public.twelve_library;
create policy "Add own library" on public.twelve_library
    for insert to authenticated
    with check ((select auth.uid()) = user_id);

drop policy if exists "Change own library" on public.twelve_library;
create policy "Change own library" on public.twelve_library
    for update to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

drop policy if exists "Remove own library" on public.twelve_library;
create policy "Remove own library" on public.twelve_library
    for delete to authenticated
    using ((select auth.uid()) = user_id);

revoke all on public.twelve_library from anon;
grant select, insert, update, delete on public.twelve_library to authenticated;

-- The time of the last change is set by the database, not by the phone
create or replace function public.twelve_library_touch()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists twelve_library_touch on public.twelve_library;
create trigger twelve_library_touch
    before insert or update on public.twelve_library
    for each row execute function public.twelve_library_touch();

-- Lets a signed in user delete their own account, and with it their library
create or replace function public.delete_my_account()
returns void
language sql
security definer
set search_path = ''
as $$
    delete from auth.users where id = auth.uid();
$$;

revoke execute on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
