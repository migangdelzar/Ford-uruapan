output "emme_1_instance_id" {
  value = try(oci_core_instance.emme["emme-1"].id, null)
}

output "emme_2_instance_id" {
  value = try(oci_core_instance.emme["emme-2"].id, null)
}

output "data_volume_id" {
  value = try(oci_core_volume.data[0].id, null)
}

output "backup_bucket_name" {
  value = try(oci_objectstorage_bucket.backups[0].name, null)
}

output "backup_bucket_namespace" {
  value = try(oci_objectstorage_bucket.backups[0].namespace, null)
}

output "backup_object_prefix" {
  value = var.backup_object_prefix
}

output "backup_dynamic_group_id" {
  value = try(oci_identity_dynamic_group.backup[0].id, null)
}

output "backup_policy_id" {
  value = try(oci_identity_policy.backup[0].id, null)
}
