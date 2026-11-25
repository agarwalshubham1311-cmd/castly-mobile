-- M7.2 account-scoped privileged-access review; no new audit permissions.
-- Rehearse lock/WAL costs on a representative restore before approved hosted execution.
begin;
create index admin_account_denials on admin_private.audit(actor_id,id desc) where outcome='denied';
commit;
