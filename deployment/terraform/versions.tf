terraform {
  required_version = ">= 1.12.0"

  backend "oci" {
    bucket    = "ford-terraform-state"
    namespace = "idadel7qesp6"
    key       = "ford-uruapan/runtime-vault-identity/terraform.tfstate"
    region    = "us-ashburn-1"
    auth      = "APIKey"
  }

  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 7.0"
    }
  }
}

provider "oci" {
  region              = var.region
  config_file_profile = var.oci_config_profile
}
