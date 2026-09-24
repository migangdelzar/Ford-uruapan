locals {
  backup_compartment_id = var.backup_compartment_ocid != "" ? var.backup_compartment_ocid : var.compartment_ocid
}

resource "oci_core_volume" "data" {
  count               = var.manage_data_volume ? 1 : 0
  availability_domain = var.availability_domain
  compartment_id      = var.compartment_ocid
  display_name        = "emme-2-data"
  size_in_gbs         = var.data_volume_size_gb
  vpus_per_gb         = 10

  lifecycle {
    prevent_destroy = true
  }
}

resource "oci_core_volume_attachment" "data" {
  count           = var.manage_data_volume && var.create_instances ? 1 : 0
  attachment_type = "paravirtualized"
  instance_id     = oci_core_instance.emme["emme-2"].id
  volume_id       = oci_core_volume.data[0].id
}

resource "oci_objectstorage_bucket" "backups" {
  count          = var.manage_backup_bucket ? 1 : 0
  compartment_id = var.compartment_ocid
  name           = var.backup_bucket_name
  namespace      = var.object_storage_namespace
  storage_tier   = "Standard"

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = var.object_storage_namespace != ""
      error_message = "object_storage_namespace is required when managing the backup bucket."
    }
  }
}

resource "oci_objectstorage_object_lifecycle_policy" "backups" {
  count     = var.manage_backup_bucket ? 1 : 0
  bucket    = oci_objectstorage_bucket.backups[0].name
  namespace = var.object_storage_namespace

  rules {
    action      = "DELETE"
    is_enabled  = true
    name        = "delete-old-ford-backups"
    target      = "objects"
    time_amount = 14
    time_unit   = "DAYS"

    object_name_filter {
      inclusion_prefixes = ["${var.backup_object_prefix}/"]
    }
  }
}

resource "oci_identity_dynamic_group" "backup" {
  count          = var.manage_backup_bucket ? 1 : 0
  compartment_id = var.tenancy_ocid
  name           = var.backup_dynamic_group_name
  description    = "Allows emme-2 to write and verify Ford backups in Object Storage."
  matching_rule  = "instance.id = '${var.emme_2_instance_ocid}'"

  lifecycle {
    precondition {
      condition     = var.emme_2_instance_ocid != ""
      error_message = "emme_2_instance_ocid is required when managing backup identity."
    }
  }
}

resource "oci_identity_policy" "backup" {
  count          = var.manage_backup_bucket ? 1 : 0
  compartment_id = var.tenancy_ocid
  name           = var.backup_policy_name
  description    = "Least-privilege Object Storage access for the Ford emme-2 backup job."

  statements = [
    "Allow dynamic-group ${oci_identity_dynamic_group.backup[0].name} to read buckets in compartment id ${local.backup_compartment_id}",
    "Allow dynamic-group ${oci_identity_dynamic_group.backup[0].name} to manage objects in compartment id ${local.backup_compartment_id} where all {target.bucket.name = '${var.backup_bucket_name}', target.object.name = '${var.backup_object_prefix}/*', any {request.permission = 'OBJECT_CREATE', request.permission = 'OBJECT_INSPECT', request.permission = 'OBJECT_READ', request.permission = 'OBJECT_DELETE'}}"
  ]

  lifecycle {
    precondition {
      condition     = local.backup_compartment_id != ""
      error_message = "A backup compartment OCID is required when managing backup identity."
    }
  }
}

resource "oci_identity_policy" "backup_lifecycle" {
  count          = var.manage_backup_bucket ? 1 : 0
  compartment_id = var.tenancy_ocid
  name           = "${var.backup_policy_name}-lifecycle"
  description    = "Allows the regional Object Storage service to enforce Ford backup retention."

  statements = [
    "Allow service objectstorage-${var.region} to manage object-family in compartment id ${local.backup_compartment_id}"
  ]

  lifecycle {
    precondition {
      condition     = local.backup_compartment_id != ""
      error_message = "A backup compartment OCID is required when managing backup lifecycle identity."
    }
  }
}
