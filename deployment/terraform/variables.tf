variable "tenancy_ocid" {
  type      = string
  sensitive = true
}

variable "user_ocid" {
  type      = string
  sensitive = true
}

variable "fingerprint" {
  type      = string
  sensitive = true
}

variable "private_key_path" {
  type      = string
  sensitive = true
}

variable "region" {
  type = string
}

variable "compartment_ocid" {
  type = string
}

variable "availability_domain" {
  type = string
}

variable "existing_subnet_id" {
  type = string
}

variable "ssh_public_key" {
  type      = string
  sensitive = true
}

variable "app_image_ocid" {
  type    = string
  default = ""
}

variable "data_image_ocid" {
  type    = string
  default = ""
}

variable "backup_bucket_name" {
  type    = string
  default = "ford-service-backups"
}

variable "object_storage_namespace" {
  type    = string
  default = ""
}

variable "admin_cidr" {
  type    = string
  default = ""
}

variable "create_instances" {
  type    = bool
  default = false
}

variable "manage_data_volume" {
  type    = bool
  default = false
}

variable "manage_backup_bucket" {
  type    = bool
  default = false
}
variable "data_volume_size_gb" {
  type    = number
  default = 80
  validation {
    condition     = var.data_volume_size_gb >= 1 && var.data_volume_size_gb <= 100
    error_message = "The data volume must be between 1 and 100 GB."
  }
}

variable "always_free_ocpus" {
  type    = number
  default = 2
}

variable "always_free_memory_gb" {
  type    = number
  default = 12
}

variable "instances" {
  type = map(object({
    image_ocid       = string
    display_name     = string
    assign_public_ip = bool
  }))
  default = {
    emme-1 = { image_ocid = "", display_name = "emme-1", assign_public_ip = true }
    emme-2 = { image_ocid = "", display_name = "emme-2", assign_public_ip = true }
  }
  validation {
    condition     = length(var.instances) == 2
    error_message = "Exactly emme-1 and emme-2 must be defined."
  }
}
